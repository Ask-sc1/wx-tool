package com.xcs.wx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xcs.wx.domain.FTSContactContent;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * FTSContactContentMapper
 *
 * @author xcs
 * @date 2024年6月14日14:24:43
 **/
public interface FTSContactContentMapper extends BaseMapper<FTSContactContent> {

    @Select("SELECT id AS docId, c0 AS alias, c1 AS nickname, '' AS remark "
            + "FROM contact_fts_v5_content "
            + "WHERE c1 = 1 AND c0 IS NOT NULL AND c0 != '' ORDER BY c0")
    List<FTSContactContent> queryV4ContactContent();
}
