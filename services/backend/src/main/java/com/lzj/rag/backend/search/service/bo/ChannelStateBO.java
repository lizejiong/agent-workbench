package com.lzj.rag.backend.search.service.bo;

import java.util.*;

/**
 * 通道执行状态、候选数及稳定错误码。
 *
 * @param channel 当前召回通道名称或通道实例。
 * @param status 当前业务操作或任务状态。
 * @param candidates 本次待领取的任务、可选解析策略或召回候选集合。
 * @param errorCode 可供调用方和运维识别的稳定错误码。
 */
public record ChannelStateBO(String channel, String status, int candidates, String errorCode, String subQuery) {
    public ChannelStateBO(String channel,String status,int candidates,String errorCode) { this(channel,status,candidates,errorCode,null); }
}
