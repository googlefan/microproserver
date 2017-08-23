package com.ycb.wxxcx.provider.controller;

import com.ycb.wxxcx.provider.cache.RedisService;
import com.ycb.wxxcx.provider.constant.GlobalConfig;
import com.ycb.wxxcx.provider.mapper.OrderMapper;
import com.ycb.wxxcx.provider.mapper.RefundMapper;
import com.ycb.wxxcx.provider.mapper.UserMapper;
import com.ycb.wxxcx.provider.utils.JsonUtils;
import com.ycb.wxxcx.provider.utils.RefundUtil;
import com.ycb.wxxcx.provider.utils.WXPayUtil;
import com.ycb.wxxcx.provider.vo.Order;
import com.ycb.wxxcx.provider.vo.Refund;
import com.ycb.wxxcx.provider.vo.User;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.*;

/**
 * Created by 杜欣源:退款（提现）记录 on 2017/8/5.
 */

@RestController
@RequestMapping("refund")
public class RefundController {

    public static final Logger logger = LoggerFactory.getLogger(RefundController.class);

    @Autowired(required = false)
    private RefundMapper refundMapper;

    @Autowired
    private RedisService redisService;

    @Autowired(required = false)
    private UserMapper userMapper;

    @Autowired(required = false)
    private OrderMapper orderMapper;

    @Value("${appID}")
    private String appID;

    @Value("${appSecret}")
    private String appSecret;

    @Value("${mch_id}")
    private String mchId;

    @Value("${key}")
    private String key;

    // 获取提现记录列表
    @RequestMapping(value = "/getRefundList", method = RequestMethod.POST)
    @ResponseBody
    public String query(@RequestParam("session") String session) {
        Map<String, Object> bacMap = new HashMap<>();
        if (StringUtils.isEmpty(session)) {
            bacMap.put("data", null);
            bacMap.put("code", 2);
            bacMap.put("msg", "失败(session不可为空)");

            return JsonUtils.writeValueAsString(bacMap);
        }
        try {
            String openid = redisService.getKeyValue(session);
            User user = this.userMapper.findUserinfoByOpenid(openid);

            List<Refund> refundList = this.refundMapper.findRefunds(user.getId());
            Map<String, Object> data = new HashMap<>();
            Map<String, Object> refundData = new HashMap<>();
            refundData.put("redund_logs",refundList);
            data.put("data", refundData);
            bacMap.put("data", data);
            bacMap.put("code", 0);
            bacMap.put("msg", "成功");

        } catch (Exception e) {
            logger.error(e.getMessage());
            bacMap.put("data", null);
            bacMap.put("code", 1);
            bacMap.put("msg", "获取数据失败");
        }
        return JsonUtils.writeValueAsString(bacMap);
    }

    // 申请提现(微信)
    @RequestMapping(value = "/doRefund", method = RequestMethod.POST)
    @ResponseBody
    public String wechatRefund(@RequestParam("session") String session) throws UnsupportedEncodingException {

        Map<String, Object> bacMap = new HashMap<>();

        if (StringUtils.isEmpty(session)) {
            bacMap.put("code", 1000);
            bacMap.put("msg", "失败(session不可为空)");
            return JsonUtils.writeValueAsString(bacMap);
        }

        String openid = redisService.getKeyValue(session);
        User user = this.userMapper.findUserMoneyByOpenid(openid);//查询用户可用余额
        if(user.getUsablemoney() == BigDecimal.ZERO){
            bacMap.put("code", 1);
            bacMap.put("msg", "账户余额不足");
            return JsonUtils.writeValueAsString(bacMap);
        }
        //根据用户uid拿订单
        List<Order> orderList =  this.orderMapper.findOrderListIdByUid(user.getId());

        if (null == orderList || 0 == orderList.size()){
            bacMap.put("code", 2);
            bacMap.put("msg", "查询失败,没有可退款的订单)");
            return JsonUtils.writeValueAsString(bacMap);
        }else {
            Refund newRefund = null;
            for (int i = 0; i < orderList.size(); i++) {
                //创建退款记录
                Refund refund = new Refund();
                BigDecimal refundMoney = (orderList.get(i).getPaid()).subtract(orderList.get(i).getUsefee());//退款金额
                refund.setRefund(refundMoney);
                refund.setStatus(1);//申请提现状态
                refund.setOrderid(orderList.get(i).getOrderid());
                refund.setUid(user.getId());
                refund.setCreatedBy("SYS:refund");
                this.refundMapper.insertRefund(refund);//写入退款记录表
                newRefund = this.refundMapper.findRefundIdByUid(user.getId());//拿退款编号

                String out_trade_no = orderList.get(i).getOrderid();//商户订单号
                String out_refund_no = newRefund.getId().toString();//商户退款编号
                Long total_fee = (orderList.get(i).getPaid().longValue())*100;//订单金额
                Long refund_fee = (refundMoney.longValue())*100;//退款总金额

                SortedMap<String, Object> parameters = new TreeMap<String, Object>();
                parameters.put("appid", appID);//公众账号ID
                parameters.put("mch_id", mchId);//商户号
                parameters.put("nonce_str", WXPayUtil.getNonce_str());//生成随机字符串
                // 在notify_url中解析微信返回的信息获取到 transaction_id，此项不是必填，详细请看上图文档
                // parameters.put("transaction_id", "微信支付订单中调用统一接口后微信返回的 transaction_id");
                parameters.put("out_trade_no", out_trade_no);//商户系统内部订单号
                parameters.put("out_refund_no", out_refund_no); //商户系统内部的退款单号，约束为UK唯一
                parameters.put("total_fee", total_fee.toString()); //订单总金额：单位为分
                parameters.put("refund_fee", refund_fee.toString()); //退款总金额：单位为分
                parameters.put("op_user_id", mchId);// 操作员帐号, 默认为商户号

                String xml = WXPayUtil.map2Xml(parameters, key);
                String createOrderURL = GlobalConfig.WX_CREATORDER_URL;

                try {
                    String mch_id = mchId;
                    Map map = RefundUtil.forRefund(createOrderURL, xml, mch_id);
                    if (map != null) {
                        String return_code = (String) map.get("return_code");//返回状态码
                        String result_code = (String) map.get("result_code");//业务结果
                        if (return_code.equals("SUCCESS") && result_code.equals("SUCCESS")) {
                            //修改退款状态为成功(2)
                            newRefund.setLastModifiedBy("SYS:refund");
                            newRefund.setRefund(refundMoney);
                            newRefund.setStatus(2);//退款成功
                            this.refundMapper.updateStatus(newRefund);
                            //减掉用户可用余额
                            user.setLastModifiedBy("SYS:refund");
                            user.setRefund(refundMoney);  //需要减掉的金额
                            this.userMapper.updateUsablemoneyByUid(user);

                            bacMap.put("code", 0);
                            bacMap.put("msg", "退款成功");
                            return JsonUtils.writeValueAsString(bacMap);

                        } else {
                            String return_msg = (String) map.get("return_msg");
                            logger.error("退款失败 退款编号："+ newRefund.getId()+"描述:"+return_msg);
                            bacMap.put("code", 5);
                            bacMap.put("msg", "退款失败，返回结果有误");
                            return JsonUtils.writeValueAsString(bacMap);
                        }
                    } else {
                        logger.error("签名失败");
                        bacMap.put("code", 4);
                        bacMap.put("msg", "签名失败，参数格式校验错误");
                        return JsonUtils.writeValueAsString(bacMap);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    bacMap.put("code", 3);
                    bacMap.put("msg", "退款失败（系统有异常）");
                    return JsonUtils.writeValueAsString(bacMap);
                }
            }
        }
        return null;
    }
}
