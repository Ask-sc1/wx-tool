package com.xcs.wx.repository.impl;

import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xcs.wx.constant.DataSourceType;
import com.xcs.wx.domain.Feeds;
import com.xcs.wx.domain.dto.FeedsDTO;
import com.xcs.wx.mapper.FeedsMapper;
import com.xcs.wx.repository.FeedsRepository;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 朋友圈 Repository 实现类
 *
 * @author xcs
 * @date 2024年01月03日 16时56分
 **/
@Repository
@DS(value = DataSourceType.SNS_DB)
public class FeedsRepositoryImpl extends ServiceImpl<FeedsMapper, Feeds> implements FeedsRepository {

    private static final Pattern CREATE_TIME_PATTERN =
            Pattern.compile("<createTime>(\\d+)</createTime>");

    @Override
    public Page<Feeds> queryFeeds(FeedsDTO feedsDTO) {
        String userName = StrUtil.isBlank(feedsDTO.getUserName()) ? null : feedsDTO.getUserName();
        List<Feeds> all = getBaseMapper().queryV4Feeds(userName);
        all.forEach(feeds -> feeds.setCreateTime(parseCreateTime(feeds.getContent())));
        List<Feeds> filtered = all.stream()
                .filter(feeds -> ObjUtil.isEmpty(feedsDTO.getStartTime())
                        || feeds.getCreateTime() >= feedsDTO.getStartTime())
                .filter(feeds -> ObjUtil.isEmpty(feedsDTO.getEndTime())
                        || feeds.getCreateTime() <= feedsDTO.getEndTime())
                .sorted(Comparator.comparing(Feeds::getCreateTime).reversed())
                .collect(Collectors.toList());
        int current = (int) Math.max(1L, feedsDTO.getCurrent() == null ? 1L : feedsDTO.getCurrent());
        int pageSize = (int) Math.max(1L, feedsDTO.getPageSize() == null ? 10L : feedsDTO.getPageSize());
        int from = Math.min(filtered.size(), (current - 1) * pageSize);
        int to = Math.min(filtered.size(), from + pageSize);
        Page<Feeds> page = new Page<>(current, pageSize, filtered.size());
        page.setRecords(filtered.subList(from, to));
        return page;
    }

    private int parseCreateTime(String content) {
        if (content == null) {
            return 0;
        }
        Matcher matcher = CREATE_TIME_PATTERN.matcher(content);
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
