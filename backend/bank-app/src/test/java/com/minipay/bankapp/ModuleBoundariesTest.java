package com.minipay.bankapp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Module boundaries, checked on the compiled classes of the assembled application.
 *
 * <p>Each module {@code com.minipay.<m>} exposes {@code com.minipay.<m>.api} and hides everything in
 * {@code com.minipay.<m>.internal}. Because each module's tables are only touched by its own internal
 * repositories, "no access to another module's internal package" also means "no reading another
 * module's tables". Combined with one Postgres schema per module, the boundary holds in code and data.
 */
class ModuleBoundariesTest {

    /** Root package of each module (artifact names differ in places, e.g. payment-hub → paymenthub). */
    static final List<String> MODULES = List.of(
            "ledger", "paymenthub", "messaging", "refdata", "compliance", "settlement", "recon", "network");

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.minipay");
    }

    @TestFactory
    Stream<DynamicTest> internals_are_private_to_their_module() {
        return MODULES.stream().map(m -> DynamicTest.dynamicTest(m, () ->
                noClasses().that().resideOutsideOfPackage("com.minipay." + m + "..")
                        .should().dependOnClassesThat().resideInAPackage("com.minipay." + m + ".internal..")
                        .because("other modules must use com.minipay." + m + ".api only")
                        .allowEmptyShould(true)
                        .check(classes)));
    }

    @TestFactory
    Stream<DynamicTest> api_does_not_leak_internals() {
        return MODULES.stream().map(m -> DynamicTest.dynamicTest(m, () ->
                noClasses().that().resideInAPackage("com.minipay." + m + ".api..")
                        .should().dependOnClassesThat().resideInAPackage("com.minipay." + m + ".internal..")
                        .allowEmptyShould(true)
                        .check(classes)));
    }

    @Test
    void common_depends_on_no_module_and_no_framework() {
        noClasses().that().resideInAPackage("com.minipay.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        MODULES.stream().map(m -> "com.minipay." + m + "..").toArray(String[]::new))
                .orShould().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..")
                .check(classes);
    }

    @Test
    void compliance_stands_on_its_own() {
        // The hub asks compliance; compliance never reaches into payments, the ledger or the network. Its
        // decisions reach the hub only as an in-process event (ComplianceDecisionMade) the hub listens to.
        noClasses().that().resideInAPackage("com.minipay.compliance..")
                .should().dependOnClassesThat().resideInAnyPackage(MODULES.stream().filter(m -> !m.equals("compliance"))
                        .map(m -> "com.minipay." + m + "..").toArray(String[]::new))
                .check(classes);
    }

    @Test
    void modules_are_free_of_cycles() {
        slices().matching("com.minipay.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void business_code_does_not_talk_to_kafka_directly() {
        // Kafka is transport: the outbox relay publishes, the network inbox and tracker consume. Nothing else touches it.
        noClasses().that().resideInAPackage("com.minipay..")
                .and().resideOutsideOfPackages("com.minipay.paymenthub.internal.outbox..", "com.minipay.network.internal..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..", "org.springframework.kafka..")
                .check(classes);
    }

    @Test
    void only_the_outbox_relay_publishes() {
        // Rule 6: a message reaches Kafka only through the transactional outbox, never sent directly.
        noClasses().that().resideInAPackage("com.minipay..")
                .and().resideOutsideOfPackage("com.minipay.paymenthub.internal.outbox..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.kafka.clients.producer.Producer")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.apache.kafka.clients.producer.KafkaProducer")
                .check(classes);
    }
}
