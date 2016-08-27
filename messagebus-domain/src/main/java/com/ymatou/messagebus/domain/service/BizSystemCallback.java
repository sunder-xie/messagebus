/**
 * (C) Copyright 2016 Ymatou (http://www.ymatou.com/).
 *
 * All rights reserved.
 */
package com.ymatou.messagebus.domain.service;

import java.io.UnsupportedEncodingException;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ymatou.messagebus.domain.model.CallbackConfig;
import com.ymatou.messagebus.domain.model.Message;
import com.ymatou.messagebus.domain.model.MessageCompensate;
import com.ymatou.messagebus.infrastructure.thread.AdjustableSemaphore;
import com.ymatou.messagebus.infrastructure.thread.SemaphorManager;


/**
 * 调用业务系统
 * 
 * @author wangxudong 2016年8月16日 下午3:18:40
 *
 */
public class BizSystemCallback implements FutureCallback<HttpResponse> {

    private static Logger logger = LoggerFactory.getLogger(BizSystemCallback.class);

    public static final Integer CONN_TIME_OUT = 5000;
    public static final Integer SOCKET_TIME_OUT = 5000;
    public static final Integer CONN_REQ_TIME_OUT = 5000;

    private static RequestConfig DEFAULT_REQUEST_CONFIG = RequestConfig.custom()
            .setConnectTimeout(CONN_TIME_OUT)
            .setSocketTimeout(SOCKET_TIME_OUT)
            .setConnectionRequestTimeout(CONN_REQ_TIME_OUT)
            .build();

    private CloseableHttpAsyncClient httpClient;

    private HttpPost httpPost;

    private AdjustableSemaphore semaphore;

    private Message message;

    private MessageCompensate messageCompensate;

    private CallbackConfig callbackConfig;

    private CallbackServiceImpl callbackServiceImpl;

    private long beginTime;

    /**
     * 构造异步回调实例
     * 
     * @param httpClient
     * @param url
     * @param body
     * @throws UnsupportedEncodingException
     */
    public BizSystemCallback(CloseableHttpAsyncClient httpClient, Message message, MessageCompensate messageCompensate,
            CallbackConfig callbackConfig, CallbackServiceImpl callbackServiceImpl)
            throws UnsupportedEncodingException {
        this.httpClient = httpClient;
        this.message = message;
        this.messageCompensate = messageCompensate;
        this.callbackConfig = callbackConfig;
        this.callbackServiceImpl = callbackServiceImpl;
        this.httpPost = new HttpPost(callbackConfig.getUrl());
        this.semaphore = SemaphorManager.get(callbackConfig.getCallbackKey());

        setContentType(callbackConfig.getContentType());
        setTimeout(callbackConfig.getTimeout());
    }


    /**
     * 设置超时
     * 
     * @param timeout
     * @return
     */
    public BizSystemCallback setTimeout(int timeout) {
        RequestConfig requestConfig = RequestConfig.copy(DEFAULT_REQUEST_CONFIG)
                // .setConnectionRequestTimeout(timeout)
                .setSocketTimeout(timeout)
                .build();
        httpPost.setConfig(requestConfig);

        return this;
    }

    /**
     * 设置Content-Type
     * 
     * @param contentType
     * @return
     */
    public BizSystemCallback setContentType(String contentType) {
        if (StringUtils.isEmpty(contentType)) {
            httpPost.setHeader("Content-Type", "application/json;charset=utf-8");
        } else {
            httpPost.setHeader("Content-Type", String.format("%s;charset=utf-8", contentType));
        }

        return this;
    }

    /**
     * 判断回调是否成功
     * 
     * @param result
     * @return
     */
    private boolean isCallbackSuccess(int statusCode, String body) {
        if (statusCode == 200 && body != null
                && (body.equalsIgnoreCase("ok") || body.equalsIgnoreCase("\"ok\""))) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * 发送POST请求
     */
    public void send() {
        try {
            beginTime = System.currentTimeMillis();

            logger.info("appcode:{}, messageUuid:{}, executing request:{}.", message.getAppCode(), message.getUuid(),
                    httpPost.getRequestLine());

            semaphore.acquire();

            String body = message.getBody();
            if (StringUtils.isEmpty(body) == false) {
                StringEntity postEntity = new StringEntity(body, "UTF-8");
                httpPost.setEntity(postEntity);

                logger.info("appcode:{}, messageUuid:{}, request body:{}.", message.getAppCode(), message.getUuid(),
                        body);;
            }
            httpClient.execute(httpPost, this);

        } catch (Exception e) {
            logger.error(String.format("biz callback accquire semaphore fail, appcode:%s, messageUuid:%s",
                    message.getAppCode(), message.getUuid()), e);
        }

    }

    /**
     * 释放资源
     */
    private void clear() {
        semaphore.release();
        httpPost.releaseConnection();
    }

    @Override
    public void completed(HttpResponse result) {
        try {
            HttpEntity entity = result.getEntity();
            String reponseStr = EntityUtils.toString(entity, "UTF-8");
            int statusCode = result.getStatusLine().getStatusCode();
            long duration = System.currentTimeMillis() - beginTime;

            logger.info("appcode:{}, messageUuid:{}, async response code:{}, duration:{}ms, message{}.",
                    message.getAppCode(), message.getUuid(), statusCode, duration, reponseStr);

            if (isCallbackSuccess(statusCode, reponseStr)) {
                callbackServiceImpl.writeSuccessResult(message, messageCompensate, callbackConfig, duration);
            } else {
                callbackServiceImpl.writeFailResult(message, messageCompensate, callbackConfig, reponseStr, duration,
                        null);
            }

        } catch (Exception e) {
            logger.error(String.format("appcode:{}, messageUuid:{}, {} completed.", message.getAppCode(),
                    message.getUuid(), httpPost.getRequestLine()), e);
        } finally {
            clear();
        }
    }

    @Override
    public void failed(Exception ex) {
        logger.error(String.format("appcode:{}, messageUuid:{}, {} cancelled.", message.getAppCode(),
                message.getUuid(), httpPost.getRequestLine()), ex);

        long duration = System.currentTimeMillis() - beginTime;
        callbackServiceImpl.writeFailResult(message, messageCompensate, callbackConfig, null, duration, ex);

        clear();

    }

    @Override
    public void cancelled() {
        logger.error("appcode:{}, messageUuid:{}, {} cancelled.", message.getAppCode(),
                message.getUuid(), httpPost.getRequestLine());

        long duration = System.currentTimeMillis() - beginTime;
        callbackServiceImpl.writeFailResult(message, messageCompensate, callbackConfig, "http cancelled", duration,
                null);

        clear();
    }


}