/**
 * (C) Copyright 2016 Ymatou (http://www.ymatou.com/).
 *
 * All rights reserved.
 */
package com.ymatou.messagebus.test.infrastructure.kafka;

import java.util.UUID;

import javax.annotation.Resource;

import org.junit.Test;

import com.ymatou.messagebus.infrastructure.kafka.KafkaMessageKey;
import com.ymatou.messagebus.infrastructure.kafka.KafkaProducerClient;
import com.ymatou.messagebus.test.BaseTest;

public class KafkaClientTest extends BaseTest {

    @Resource
    private KafkaProducerClient kafkaClient;

    @Test
    public void testSendAsync() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            KafkaMessageKey kafkaMessageKey =
                    new KafkaMessageKey("demo", "hello", UUID.randomUUID().toString(), String.valueOf(i));

            kafkaClient.sendAsync("messagebus.demo", kafkaMessageKey, "tony" + i);
        }
    }
}
