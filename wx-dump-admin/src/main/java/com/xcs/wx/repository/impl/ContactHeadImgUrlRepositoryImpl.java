package com.xcs.wx.repository.impl;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.xcs.wx.constant.DataSourceType;
import com.xcs.wx.mapper.ContactHeadImgMapper;
import com.xcs.wx.repository.ContactHeadImgUrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联系人头像 Repository 实现类
 *
 * @author xcs
 * @date 2023年12月21日18:38:19
 */
@Repository
@RequiredArgsConstructor
@DS(value = DataSourceType.HEAD_IMAGE_DB)
public class ContactHeadImgUrlRepositoryImpl implements ContactHeadImgUrlRepository {

    private final ContactHeadImgMapper contactHeadImgMapper;

    @Override
    public Map<String, String> queryHeadImgUrl(List<String> usrNames) {
        if (usrNames == null || usrNames.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new HashMap<>();
        for (String userName : usrNames) {
            String avatar = queryHeadImgUrlByUserName(userName);
            if (avatar != null) {
                result.put(userName, avatar);
            }
        }
        return result;
    }

    @Override
    public String queryHeadImgUrlByUserName(String userName) {
        com.xcs.wx.domain.ContactHeadImg avatar = contactHeadImgMapper.getContactHeadImg(userName);
        return avatar == null || avatar.getSmallHeadBuf() == null || avatar.getSmallHeadBuf().length == 0
                ? null
                : "/api/contact/headImg/avatar?userName=" + userName;
    }
}
