package com.xcs.wx.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 最近使用关键字
 *
 * @author xcs
 * @date 2024年01月23日 11时09分
 **/
@Data
@TableName("search_dict_v1")
public class FTSRecentUsed {

    /**
     * 查询的内容
     */
    @TableId("content")
    private String queryText;

    /**
     * 用户名
     */
    @TableField("click_type")
    private Integer username;

    /**
     * 修改时间
     */
    @TableField("update_time")
    private String updateTime;
}
