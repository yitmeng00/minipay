package com.minipay.paymenthub.internal.outbox;

import com.minipay.paymenthub.api.PaymentEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
}
