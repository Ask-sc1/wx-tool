package com.xcs.wx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xcs.wx.domain.Feeds;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 朋友圈 Mapper
 *
 * @author xcs
 * @date 2023年12月22日 13时51分
 **/
public interface FeedsMapper extends BaseMapper<Feeds> {

    List<Feeds> queryV4Feeds(@Param("userName") String userName);
}
