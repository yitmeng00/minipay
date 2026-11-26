package com.minipay.paymenthub.internal.outbox;

import com.minipay.paymenthub.api.PaymentEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import com.minipay.paymenthub.internal.network.NetworkGateway;

/**
 * Topics are created by the application (auto-creation is off in docker-compose so a typo cannot
 * silently create a new topic). One topic per bank; key = UETR so a payment's events stay in order.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTopicsConfiguration {

    public static String paymentEventsTopic(String bankBic) {
        return "minipay." + bankBic + PaymentEvents.TOPIC_SUFFIX;
    }

    @Bean
    NewTopic paymentEventsTopicBean(@Value("${minipay.bank.bic}") String bankBic) {
        return TopicBuilder.name(paymentEventsTopic(bankBic)).partitions(3).replicas(1).build();
    }

    /**
     * The network: one inbox per simulated bank, plus one for banks outside the simulator. Every bank
     * declares all of them (creating an existing topic is a no-op), so a bank can send to another that
     * has not started yet: the message waits in the inbox, as on a store-and-forward network.
     */
    @Bean
    KafkaAdmin.NewTopics networkTopics(@Value("${minipay.network.participants:}") java.util.List<String> participants) {
        java.util.List<NewTopic> topics = new java.util.ArrayList<>();
        topics.add(TopicBuilder.name(NetworkGateway.EXTERNAL_TOPIC).partitions(3).replicas(1).build());
        participants.stream().filter(b -> !b.isBlank()).forEach(b ->
                topics.add(TopicBuilder.name(NetworkGateway.inboxTopic(b)).partitions(3).replicas(1).build()));
        return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
    }
}
