package cn.hkfdt.xiaot.web.weixin;

import cn.hkfdt.xiaot.util.MapUtil;
import cn.hkfdt.xiaot.util.SHAUtil;
import cn.hkfdt.xiaot.websocket.utils.HttpUtils;
import com.alibaba.fastjson.JSON;
import com.mysql.jdbc.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by whyse
 * on 2017/2/21 15:30
 */
@Controller
public class WxCommonController {

    Logger logger = LoggerFactory.getLogger(WxCommonController.class);

    /**
     * 用于微信服务端和本服务认证
     * @param signature
     * @param timestamp
     * @param nonce
     * @param echostr
     * @return
     */
    @RequestMapping("/white/wx/server/validate")
    @ResponseBody
    public String validate(@RequestParam(required = true) String signature,
                           @RequestParam(required = true)String timestamp, @RequestParam(required = true)String nonce,
                           @RequestParam(required = true)String echostr) {
        String[] strs = {WXHelper.wxToken,timestamp,nonce};
        Arrays.sort(strs);
        StringBuffer sb = new StringBuffer();
        for(String item : strs){
            sb.append(item);
        }
        String validateStr = SHAUtil.hex_sha1(sb.toString());
//        logger.info("validateStr:"+validateStr);
        if(validateStr.equals(signature)){
            return echostr;
        }
        return "error";
    }
    /*
   参数	描述
openid	用户的唯一标识
nickname	用户昵称
sex	用户的性别，值为1时是男性，值为2时是女性，值为0时是未知
province	用户个人资料填写的省份
city	普通用户个人资料填写的城市
country	国家，如中国为CN
headimgurl	用户头像，最后一个数值代表正方形头像大小（有0、46、64、96、132数值可选，0代表640*640正方形头像），用户没有头像时该项为空。若用户更换头像，原有头像URL将失效。
privilege	用户特权信息，json 数组，如微信沃卡用户为（chinaunicom）
unionid	只有在用户将公众号绑定到微信开放平台帐号后，才会出现该字段。详见：获取用户个人信息（UnionID机制）
    */
    @RequestMapping("/white/wx/getuserinfo")
    @ResponseBody
    public Object getuserinfo(String code, @RequestParam(required = true)String state) {
        Map<String,Object>  mapTar = new HashMap<>(4);
        if(StringUtils.isNullOrEmpty(code)){
            mapTar = MapUtil.getErrorMap(201,"微信用户不同意授权");
            return mapTar;
        }
        //2.获取通过code换取网页授权access_token
        String url = WXHelper.getReqAccTokenUrl(code);
        String jsonStr = HttpUtils.httpGet(url);
        Map<String,Object> mapTemp = JSON.parseObject(jsonStr);

        //3.刷新access_token
        if(!mapTemp.containsKey("errcode")){
            url = WXHelper.getAccTokenRefUrl(mapTemp);
            jsonStr = HttpUtils.httpGet(url);
            mapTemp = JSON.parseObject(jsonStr);

            //4.拉取用户信息(需scope为 snsapi_userinfo)
            if(!mapTemp.containsKey("errcode")){
                url = WXHelper.getUserInfoUrl(mapTemp);
                jsonStr = HttpUtils.httpGet(url);
                mapTemp = JSON.parseObject(jsonStr);//获取到用户的信息

                String nickname = mapTemp.get("nickname").toString();
                String openid = mapTemp.get("openid").toString();
                mapTar.put("userName",nickname);
                mapTar.put("userId",openid);
                mapTar.put("userType",2);

                return mapTar;
            }
        }
        mapTar = MapUtil.getErrorMap(202,"微信授权未知错误");
        return mapTar;
    }
}