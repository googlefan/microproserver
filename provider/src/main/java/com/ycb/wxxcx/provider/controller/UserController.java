package com.ycb.wxxcx.provider.controller;

import com.ycb.wxxcx.provider.cache.RedisService;
import com.ycb.wxxcx.provider.mapper.UserMapper;
import com.ycb.wxxcx.provider.utils.HttpRequest;
import com.ycb.wxxcx.provider.utils.JsonUtils;
import com.ycb.wxxcx.provider.utils.MD5;
import com.ycb.wxxcx.provider.vo.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by zhuhui on 17-6-16.
 */
@RestController
@RequestMapping("user")
public class UserController {
    public static final Logger logger = LoggerFactory.getLogger(UserController.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisService redisService;

    @Value("${appID}")
    private String appID;

    @Value("${appSecret}")
    private String appSecret;

    @RequestMapping(value = "/login", method = RequestMethod.POST)
    @ResponseBody
    public String login(@RequestParam("code") String code,
                        @RequestParam("encryptedData") String encryptedData,
                        @RequestParam("iv") String iv) {
        // 通过微信接口,根据code获取sessionkey和openid
        String param = "appid=" + appID + "&secret=" + appSecret + "&js_code=" + code + "&grant_type=authorization_code";
        Map<String, Object> bacMap = new HashMap<>();
        try {
            String getLoginInfo = HttpRequest.sendGet("https://api.weixin.qq.com/sns/jscode2session", param);
            Map<String, Object> loginInfoMap = JsonUtils.readValue(getLoginInfo);
            String sessinKey = (String) loginInfoMap.get("session_key");
            String openid = (String) loginInfoMap.get("openid");
            Integer expiresIn = (Integer) loginInfoMap.get("expires_in");

            // 根据openid检索数据库，不存在新建用户
            Integer optlock = this.userMapper.findByOpenid(openid);
            if (optlock == null) {
                User user = new User();
                user.setOpenid(openid);
                user.setDeposit(BigDecimal.ZERO);
                user.setRefund(BigDecimal.ZERO);
                user.setUsablemoney(BigDecimal.ZERO);
                user.setPlatform(3);
                user.setCreatedBy("system");
                user.setCreatedDate(new Date());
                this.userMapper.insert(user);
            } else {
                optlock++;
                this.userMapper.update(optlock, new Date(), openid);
            }
            // 生成第三方session
            String session = MD5.getMessageDigest((sessinKey + encryptedData + iv).getBytes());
            // 设置session过期
            redisService.setKeyValueTimeout(session, openid, expiresIn);
            Map<String, Object> data = new HashMap<>();
            data.put("session", session);
            bacMap.put("data", data);
            bacMap.put("code", 0);
            bacMap.put("msg", "成功");
        } catch (Exception e) {
            logger.error(e.getMessage());
            bacMap.put("data", null);
            bacMap.put("code", 0);
            bacMap.put("msg", "失败");
        }
        return JsonUtils.writeValueAsString(bacMap);
    }


    // 获取用户基本信息 用户头像，用户昵称，用户账户余额
    @RequestMapping(value = "/userInfo", method = RequestMethod.POST)
    public String query(@RequestParam("session") String session) {
        Map<String, Object> bacMap = new HashMap<>();
        try {
            String openid = redisService.getKeyValue(session);
            User user = this.userMapper.findUserinfoByOpenid(openid);
            Map<String, Object> data = new HashMap<>();
            data.put("user_info", user);
            bacMap.put("data", data);
            bacMap.put("code", 0);
            bacMap.put("msg", "成功");
        } catch (Exception e) {
            bacMap.put("data", null);
            bacMap.put("code", 0);
            bacMap.put("msg", "失败");
        }
        return JsonUtils.writeValueAsString(bacMap);
    }

    // 根据用户code获取用户openid和session_key
    @RequestMapping(value = "/getUserOpenId", method = RequestMethod.POST)
    public String getUserOpenId(@RequestParam String code) {
        String param = "appid=" + appID + "&secret" + appSecret + "&js_code" + code + "&grant_type=authorization_code";
        return HttpRequest.sendGet("https://api.weixin.qq.com/sns/jscode2session", param);
    }

    // 提现接口 WithdrawMoney
    // 提现记录 WithdrawHistory
}