package com.xcs.wx.repository.impl;

import com.baomidou.dynamic.datasource.annotation.DS;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xcs.wx.constant.DataSourceType;
import com.xcs.wx.domain.FTSContactContent;
import com.xcs.wx.domain.dto.RecoverContactDTO;
import com.xcs.wx.mapper.FTSContactContentMapper;
import com.xcs.wx.repository.FTSContactContentRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 联系人内容 Repository 实现类
 *
 * @author xcs
 * @date 2024年6月14日15:18:11
 **/
@Repository
@DS(value = DataSourceType.FTS_CONTACT_DB)
public class FTSContactContentRepositoryImpl extends ServiceImpl<FTSContactContentMapper, FTSContactContent> implements FTSContactContentRepository {

    @Override
    public List<FTSContactContent> queryContactContent(RecoverContactDTO recoverContactDTO) {
        // 构建查询条件
        Map<String, FTSContactContent> unique = new LinkedHashMap<>();
        getBaseMapper().queryV4ContactContent().stream()
                .map(this::parseSearchDocument)
                .filter(item -> StrUtil.isNotBlank(item.getAlias()))
                .filter(item -> StrUtil.isBlank(recoverContactDTO.getNickname())
                        || StrUtil.contains(item.getNickname(), recoverContactDTO.getNickname()))
                .filter(item -> StrUtil.isBlank(recoverContactDTO.getRemark())
                        || StrUtil.contains(item.getRemark(), recoverContactDTO.getRemark()))
                .forEach(item -> unique.putIfAbsent(
                        item.getAlias() + "\u0000" + item.getNickname(), item));
        return unique.values().stream().collect(Collectors.toList());
    }

    private FTSContactContent parseSearchDocument(FTSContactContent raw) {
        String[] parts = raw.getAlias() == null
                ? new String[0]
                : raw.getAlias().replace("\u0000", "").split("\u0008+");
        FTSContactContent parsed = new FTSContactContent();
        parsed.setDocId(raw.getDocId());
        parsed.setRemark(parts.length > 0 ? parts[0].trim() : "");
        parsed.setNickname(parts.length > 1 ? parts[1].trim() : parsed.getRemark());
        parsed.setAlias(parts.length > 2 ? parts[2].trim() : parsed.getRemark());
        if (StrUtil.isBlank(parsed.getAlias())) {
            parsed.setAlias(parsed.getNickname());
        }
        return parsed;
    }
}
