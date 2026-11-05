package com.xcs.wx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xcs.wx.domain.Msg;
import com.xcs.wx.domain.vo.CountRecentMsgsVO;
import com.xcs.wx.domain.vo.MsgTypeDistributionVO;
import com.xcs.wx.domain.vo.TopContactsVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息 Mapper
 *
 * @author xcs
 * @date 2023年12月22日 13时51分
 **/
public interface MsgMapper extends BaseMapper<Msg> {

    /**
     * 微信消息类型及其分布统计
     *
     * @return MsgTypeDistributionVO
     */
    List<MsgTypeDistributionVO> msgTypeDistribution();

    /**
     * 统计过去 15 天每天的发送和接收消息数量
     *
     * @return MsgTrendVO
     */
    List<CountRecentMsgsVO> countRecentMsgs();

    /**
     * 最近一个月内微信互动最频繁的前10位联系人
     *
     * @return MsgRankVO
     */
    List<TopContactsVO> topContacts();

    /**
     * 统计发送消息数量
     *
     * @return 消息数量
     */
    int countSent();

    /**
     * 统计接受消息数量
     *
     * @return 消息数量
     */
    int countReceived();

    List<String> listV4MessageTables();

    List<String> listV4UserNames();

    int messageTableExists(@Param("tableName") String tableName);

    List<Msg> queryV4Messages(
            @Param("tableName") String tableName,
            @Param("talker") String talker,
            @Param("nextSequence") Long nextSequence,
            @Param("limit") int limit,
            @Param("currentUser") String currentUser);

    List<Msg> exportV4Messages(
            @Param("tableName") String tableName,
            @Param("talker") String talker,
            @Param("currentUser") String currentUser);

    List<MsgTypeDistributionVO> v4MsgTypeDistribution(@Param("tableName") String tableName);

    List<CountRecentMsgsVO> v4CountRecentMsgs(
            @Param("tableName") String tableName,
            @Param("currentUser") String currentUser);

    List<TopContactsVO> v4TopContacts(
            @Param("tableName") String tableName,
            @Param("talker") String talker);

    int v4CountSent(
            @Param("tableName") String tableName,
            @Param("currentUser") String currentUser);

    int v4CountReceived(
            @Param("tableName") String tableName,
            @Param("currentUser") String currentUser);
}
