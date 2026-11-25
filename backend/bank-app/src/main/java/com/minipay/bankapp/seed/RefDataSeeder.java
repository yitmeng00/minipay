package com.minipay.bankapp.seed;

import com.minipay.refdata.api.RefDataApi;
import com.minipay.refdata.api.RoutingRuleImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Loads this bank's routing table. The rest of the reference data (BIC directory, IBAN registry,
 * calendars) is the same for every bank and comes with the refdata module's migrations; routing is
 * the bank's own decision, so it is per profile.
 *
 * <p>Goes through the public import API: idempotent per rule id, and a changed rule under an existing
 * id is refused. To change a route, publish a new rule (POST /api/refdata/routing-rules), never edit the seed.
 */
@Component
class RefDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RefDataSeeder.class);

    private final SeedProperties seed;
    private final RefDataApi refdata;

    RefDataSeeder(SeedProperties seed, RefDataApi refdata) {
        this.seed = seed;
        this.refdata = refdata;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed.enabled() || seed.routingRules().isEmpty()) {
            return;
        }
        refdata.importRoutingRules(seed.routingRules().stream()
                .map(r -> new RoutingRuleImport(r.id(), r.destination(), r.currency(), r.method(), r.nextHop(),
                        r.settlementAccount(), r.priority(), r.from(), r.to(), r.release()))
                .toList());
        log.info("Reference data seed: {} routing rules loaded", seed.routingRules().size());
    }
}
