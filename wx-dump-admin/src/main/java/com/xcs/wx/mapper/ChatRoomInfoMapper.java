package com.xcs.wx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xcs.wx.domain.ChatRoomInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 群聊详情 Mapper
 *
 * @author xcs
 * @date 2024年01月09日 15时20分
 **/
public interface ChatRoomInfoMapper extends BaseMapper<ChatRoomInfo> {

    @Select("SELECT username_ AS chatRoomName, "
            + "announcement_ AS announcement, "
            + "announcement_editor_ AS announcementEditor, "
            + "announcement_publish_time_ AS announcementPublishTime, "
            + "chat_room_status_ AS chatRoomStatus "
            + "FROM chat_room_info_detail "
            + "WHERE username_ = #{chatRoomName} LIMIT 1")
    ChatRoomInfo queryChatRoomInfoDetail(@Param("chatRoomName") String chatRoomName);
}
