package com.xcs.wx.repository.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.xcs.wx.constant.DataSourceType;
import com.xcs.wx.domain.Msg;
import com.xcs.wx.domain.vo.CountRecentMsgsVO;
import com.xcs.wx.domain.vo.MsgTypeDistributionVO;
import com.xcs.wx.domain.vo.TopContactsVO;
import com.xcs.wx.mapper.MsgMapper;
import com.xcs.wx.repository.MsgRepository;
import com.xcs.wx.service.UserService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 微信 4.x 消息 Repository 实现。
 *
 * <p>微信 4.x 将每个会话拆成 {@code Msg_<md5(username)>} 表，并分散到
 * {@code message_0.db ... message_N.db}。因此消息查询需要先根据 wxid
 * 找到表和数据库，不能继续使用微信 3.x 的单一 {@code MSG} 表。</p>
 */
@Repository
@RequiredArgsConstructor
public class MsgRepositoryImpl implements MsgRepository {

    private static final Pattern XML_TITLE_PATTERN =
            Pattern.compile("<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> SYSTEM_TALKERS = new HashSet<>(java.util.Arrays.asList(
            "newsapp", "notifymessage", "filehelper", "fmessage", "medianote", "floatbottle"
    ));

    private final MsgMapper msgMapper;
    private final UserService userService;

    @Override
    public List<Msg> queryMsgByTalker(String talker, Long nextSequence) {
        List<MessageTable> messageTables = findMessageTables(talker);
        if (messageTables.isEmpty()) {
            return Collections.emptyList();
        }
        List<Msg> messages = new ArrayList<>();
        for (MessageTable messageTable : messageTables) {
            withDataSource(messageTable.dataSource);
            try {
                List<Msg> found = msgMapper.queryV4Messages(
                        messageTable.tableName, talker, nextSequence, 20, userService.currentUser());
                if (found != null) {
                    normalizeMessageContent(found);
                    messages.addAll(found);
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return messages.stream()
                .sorted(Comparator.comparing(Msg::getSequence,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .collect(Collectors.toList());
    }

    @Override
    public List<Msg> exportMsg(String talker) {
        List<MessageTable> messageTables = findMessageTables(talker);
        if (messageTables.isEmpty()) {
            return Collections.emptyList();
        }
        List<Msg> messages = new ArrayList<>();
        for (MessageTable messageTable : messageTables) {
            withDataSource(messageTable.dataSource);
            try {
                List<Msg> found = msgMapper.exportV4Messages(
                        messageTable.tableName, talker, userService.currentUser());
                if (found != null) {
                    normalizeMessageContent(found);
                    messages.addAll(found);
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        messages.sort(Comparator.comparing(Msg::getSequence,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return messages;
    }

    @Override
    public List<MsgTypeDistributionVO> msgTypeDistribution() {
        Map<String, Integer> totals = new HashMap<>();
        for (MessageTable table : allMessageTables()) {
            withDataSource(table.dataSource);
            try {
                for (MsgTypeDistributionVO item : msgMapper.v4MsgTypeDistribution(table.tableName)) {
                    totals.merge(item.getType(), item.getValue() == null ? 0 : item.getValue(), Integer::sum);
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return totals.entrySet().stream()
                .map(item -> new MsgTypeDistributionVO(item.getKey(), item.getValue()))
                .sorted(Comparator.comparing(MsgTypeDistributionVO::getValue).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<CountRecentMsgsVO> countRecentMsgs() {
        Map<String, Long> totals = new HashMap<>();
        for (MessageTable table : allMessageTables()) {
            withDataSource(table.dataSource);
            try {
                for (CountRecentMsgsVO item : msgMapper.v4CountRecentMsgs(
                        table.tableName, userService.currentUser())) {
                    String key = item.getType() + "\u0000" + item.getCategory();
                    totals.merge(key, item.getValue() == null ? 0L : item.getValue(), Long::sum);
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        List<CountRecentMsgsVO> result = new ArrayList<>();
        totals.forEach((key, value) -> {
            String[] parts = key.split("\u0000", 2);
            CountRecentMsgsVO item = new CountRecentMsgsVO();
            item.setType(parts[0]);
            item.setCategory(parts[1]);
            item.setValue(value);
            result.add(item);
        });
        return result;
    }

    @Override
    public List<TopContactsVO> topContacts() {
        Map<String, Long> totals = new HashMap<>();
        for (MessageTable table : allMessageTables()) {
            if (table.talker.endsWith("@chatroom") || SYSTEM_TALKERS.contains(table.talker)) {
                continue;
            }
            withDataSource(table.dataSource);
            try {
                for (TopContactsVO item : msgMapper.v4TopContacts(table.tableName, table.talker)) {
                    totals.merge(item.getUserName(),
                            item.getTotal() == null ? 0L : item.getTotal(), Long::sum);
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return totals.entrySet().stream()
                .map(item -> {
                    TopContactsVO contact = new TopContactsVO();
                    contact.setUserName(item.getKey());
                    contact.setTotal(item.getValue());
                    return contact;
                })
                .sorted(Comparator.comparing(TopContactsVO::getTotal).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    @Override
    public int countSent() {
        long total = 0L;
        for (MessageTable table : allMessageTables()) {
            withDataSource(table.dataSource);
            try {
                total += msgMapper.v4CountSent(table.tableName, userService.currentUser());
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    @Override
    public int countReceived() {
        long total = 0L;
        for (MessageTable table : allMessageTables()) {
            withDataSource(table.dataSource);
            try {
                total += msgMapper.v4CountReceived(table.tableName, userService.currentUser());
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private List<MessageTable> findMessageTables(String talker) {
        if (StrUtil.isBlank(talker)) {
            return Collections.emptyList();
        }
        String tableName = "Msg_" + md5(talker);
        List<MessageTable> result = new ArrayList<>();
        for (String dataSource : DataSourceType.getV4MessageDb()) {
            withDataSource(dataSource);
            try {
                if (msgMapper.messageTableExists(tableName) > 0) {
                    result.add(new MessageTable(dataSource, tableName, talker));
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return result;
    }

    private List<MessageTable> allMessageTables() {
        List<MessageTable> result = new ArrayList<>();
        for (String dataSource : DataSourceType.getV4MessageDb()) {
            withDataSource(dataSource);
            try {
                List<String> tableNames = msgMapper.listV4MessageTables();
                Map<String, String> names = tableNamesToTalkers(dataSource);
                for (String tableName : tableNames) {
                    String talker = names.get(tableName);
                    if (talker != null) {
                        result.add(new MessageTable(dataSource, tableName, talker));
                    }
                }
            } finally {
                DynamicDataSourceContextHolder.clear();
            }
        }
        return result;
    }

    private Map<String, String> tableNamesToTalkers(String dataSource) {
        Map<String, String> result = new HashMap<>();
        for (String tableName : msgMapper.listV4MessageTables()) {
            // 这里从 Name2Id 构造反查；为避免额外 Mapper 方法，直接使用会话表名规则。
            result.put(tableName, null);
        }
        List<String> userNames = msgMapper.listV4UserNames();
        for (String userName : userNames) {
            result.put("Msg_" + md5(userName), userName);
        }
        return result;
    }

    private void withDataSource(String dataSource) {
        DynamicDataSourceContextHolder.push(dataSource);
    }

    private void normalizeMessageContent(List<Msg> messages) {
        for (Msg message : messages) {
            String content = decodeContent(message.getRawContent());
            if (StrUtil.isNotBlank(content)) {
                message.setStrContent(content);
            }
            // 微信 4.x 的 compress_content 多为 zstd/protobuf，不能继续交给 3.x 的 LZ4 策略。
            message.setCompressContent(null);
            if (message.getType() != null && message.getType() == 49 && content != null
                    && content.trim().startsWith("<")) {
                Matcher matcher = XML_TITLE_PATTERN.matcher(content);
                message.setStrContent(matcher.find()
                        ? "[应用消息] " + matcher.group(1).trim()
                        : "[应用消息]");
            }
        }
    }

    private String decodeContent(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        byte[] decoded = data;
        if (data.length >= 4
                && (data[0] & 0xff) == 0x28
                && (data[1] & 0xff) == 0xb5
                && (data[2] & 0xff) == 0x2f
                && (data[3] & 0xff) == 0xfd) {
            try (ZstdCompressorInputStream input =
                         new ZstdCompressorInputStream(new ByteArrayInputStream(data));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
                decoded = output.toByteArray();
            } catch (IOException ignored) {
                return null;
            }
        }
        return new String(decoded, StandardCharsets.UTF_8).replace("\u0000", "");
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static final class MessageTable {
        private final String dataSource;
        private final String tableName;
        private final String talker;

        private MessageTable(String dataSource, String tableName, String talker) {
            this.dataSource = dataSource;
            this.tableName = tableName;
            this.talker = talker;
        }
    }
}
