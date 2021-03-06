/**
 * (C) Copyright 2016 Ymatou (http://www.ymatou.com/).
 *
 * All rights reserved.
 */
package com.ymatou.messagebus.domain.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import com.ymatou.messagebus.infrastructure.thread.ScheduledExecutorHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import com.ymatou.messagebus.domain.cache.ConfigCache;
import com.ymatou.messagebus.domain.model.*;
import com.ymatou.messagebus.domain.repository.MessageCompensateRepository;
import com.ymatou.messagebus.domain.repository.MessageRepository;
import com.ymatou.messagebus.facade.BizException;
import com.ymatou.messagebus.facade.ErrorCode;
import com.ymatou.messagebus.facade.enums.MQTypeEnum;
import com.ymatou.messagebus.facade.enums.MessageCompensateSourceEnum;
import com.ymatou.messagebus.facade.enums.MessageNewStatusEnum;
import com.ymatou.messagebus.facade.enums.MessageProcessStatusEnum;
import com.ymatou.messagebus.infrastructure.cluster.AutoResetHealthProxy;
import com.ymatou.messagebus.infrastructure.config.RabbitMQConfig;
import com.ymatou.messagebus.infrastructure.rabbitmq.MessageProducer;

/**
 * @author wangxudong 2016年8月1日 下午6:22:34
 */
@Component
public class MessageBusService implements InitializingBean,DisposableBean {

    private static Logger logger = LoggerFactory.getLogger(MessageBusService.class);

    @Resource
    private MessageRepository messageRepository;

    @Resource
    private ConfigCache configCache;

    @Resource
    private MessageCompensateRepository compensateRepository;

    @Resource
    private RabbitMQConfig rabbitMQConfig;

    @Resource
    private TaskExecutor taskExecutor;

    private AutoResetHealthProxy autoResetHealthProxy;

    /**
     * mongoDB日志专用线程池
     */
    private ExecutorService mongoDBLogExecutor = new ThreadPoolExecutor(3, 10,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(10000));

    /**
     * 发布消息
     * 
     * @param message
     */
    public void publish(Message message) {
        AppConfig appConfig = configCache.getAppConfig(message.getAppId());
        if (appConfig == null) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid appId:" + message.getAppId());
        }

        if (MQTypeEnum.Kafka.code().equals(appConfig.getMqType())) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT,
                    "invalid appId:" + message.getAppId() + ", please config mqtype to rabbitmq.");
        }

        MessageConfig messageConfig = appConfig.getMessageConfig(message.getCode());
        if (messageConfig == null || Boolean.FALSE.equals(messageConfig.getEnable())) {
            throw new BizException(ErrorCode.ILLEGAL_ARGUMENT, "invalid code:" + message.getCode());
        }

        try {
            if (autoResetHealthProxy.isHealth()) {
                String requestId = MDC.get("logPrefix");

                // 记录消息日志
                if (messageConfig.getEnableLog()) {
                    writeMongoAsync(message, requestId);
                }

                // 异步发送消息
                publishToMQAsync(message, messageConfig, requestId);

            } else {
                publishToMQ(message, messageConfig, false);
            }

        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.error(String.format("write message to mongo fail,appCode:%s,uuid:%s.", message.getAppCode(),
                    message.getUuid()), ex);
            autoResetHealthProxy.setBroken();
            publishToMQ(message, messageConfig, false);
        }
    }

    /**
     * 异步写消息日志
     *
     * @param message
     */
    private void writeMongoAsync(Message message, String requestId) {
        try {
            mongoDBLogExecutor.submit(() -> {
                MDC.put("logPrefix", requestId);

                try {
                    messageRepository.insert(message);
                } catch (Exception e) {
                    logger.error("write mongodb log failed.", e);
                }
            });
        } catch (Exception e) {
            logger.error("write mongodb log thread pool used up", e);
        }
    }


    /**
     * 异步发送消息
     *
     * @param message
     */
    private void publishToMQAsync(Message message, MessageConfig messageConfig, String requestId) {
        taskExecutor.execute(() -> {

            MDC.put("logPrefix", requestId);

            logger.info(
                    "----------------------------- async publish message begin -------------------------------");

            try {
                publishToMQ(message, messageConfig, true);

            } catch (Exception e) {
                logger.error("async publish message failed, appcode:" + message.getAppCode(), e);
            }


            logger.info(
                    "----------------------------- async publish message end -------------------------------");
        });
    }

    /**
     * 发布消息到MQ
     *
     * @param message
     */
    public void publishToMQ(Message message, MessageConfig messageConfig, boolean alreadyWriteMessage) {
        try {
            MessageProducer producer =
                    MessageProducer.newInstance(rabbitMQConfig, message.getAppId(), message.getAppCode());

            if (producer.isHealth()) {
                producer.publishMessage(message.getBody(), message.getMessageId(), message.getUuid());
            } else {
                if (producer.isBroken() == false) {
                    producer.setBroken(true);
                    logger.error("rabbitmq is broken, change to mongodb, appcode:{}", message.getAppCode());
                }
                publishToCompensate(message, messageConfig, alreadyWriteMessage);
            }

        } catch (Exception e) {
            throw new BizException(ErrorCode.MESSAGE_PUBLISH_FAIL, "appcode:" + message.getAppCode(), e);
        }
    }

    /**
     * 发布消息到补偿库
     *
     * @param message
     */
    private void publishToCompensate(Message message, MessageConfig messageConfig, boolean alreadyWriteMessage) {
        for (CallbackConfig callbackConfig : messageConfig.getCallbackCfgList()) {
            MessageCompensate messageCompensate =
                    MessageCompensate.from(message, callbackConfig, MessageCompensateSourceEnum.Publish);
            //FIXME:怎么处理部分callback补偿插入成功，部分失败呢
            compensateRepository.insert(messageCompensate);
        }

        if (alreadyWriteMessage) {
            // 分发进入补单
            messageRepository.updateMessageStatus(message.getAppId(), message.getCode(), message.getUuid(),
                    MessageNewStatusEnum.PublishToCompensate, MessageProcessStatusEnum.Init);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        autoResetHealthProxy = new AutoResetHealthProxy(1000 * 60);

    }

    @Override
    public void destroy() throws Exception {
        mongoDBLogExecutor.shutdown();
    }
}
