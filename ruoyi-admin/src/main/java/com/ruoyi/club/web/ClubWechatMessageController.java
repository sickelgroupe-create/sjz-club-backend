package com.ruoyi.club.web;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.club.service.ClubWechatMessageCrypto;
import com.ruoyi.club.service.ClubWechatMessageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** JSON safe-mode transport. Unhandled business notifications are never acknowledged as success. */
@RestController
@RequestMapping("/app/wechat/message")
public class ClubWechatMessageController
{
    @org.springframework.beans.factory.annotation.Autowired private ClubWechatMessageService messages;
    @Value("${WECHAT_MESSAGE_TOKEN:}") private String token;
    @Value("${WECHAT_MESSAGE_AES_KEY:}") private String aesKey;
    @Value("${club.wechat-app-id:}") private String appId;
    private ClubWechatMessageCrypto crypto(){return new ClubWechatMessageCrypto(token,aesKey,appId);}

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam String signature,@RequestParam String timestamp,@RequestParam String nonce,@RequestParam String echostr)
    {
        try {
            if(echostr.length()>512)return ResponseEntity.badRequest().body("invalid");
            crypto().verify(signature,timestamp,nonce,null);
            return ResponseEntity.ok().header("Content-Type","text/plain;charset=UTF-8").body(echostr);
        } catch(IllegalArgumentException error){return ResponseEntity.status(403).body("invalid");}
    }

    @PostMapping(consumes="application/json")
    public ResponseEntity<String> receive(@RequestParam("msg_signature") String signature,@RequestParam String timestamp,@RequestParam String nonce,@RequestBody String body)
    {
        try {
            if(body.length()>131072 || Math.abs(System.currentTimeMillis()/1000-Long.parseLong(timestamp))>600)return ResponseEntity.status(403).body("invalid");
            JSONObject envelope=JSON.parseObject(body);String encrypted=envelope.getString("Encrypt");
            if(encrypted==null)return ResponseEntity.status(403).body("invalid");
            ClubWechatMessageCrypto cipher=crypto();cipher.verify(signature,timestamp,nonce,encrypted);
            JSONObject event=JSON.parseObject(cipher.decrypt(encrypted));
            if("debug_demo".equals(event.getString("Event")))return ResponseEntity.ok("success");
            try {
                JSONObject reply=messages.handle(event);
                if(reply==null)return ResponseEntity.ok("success");
                String time=Long.toString(System.currentTimeMillis()/1000),value=cipher.encrypt(reply.toJSONString());
                JSONObject response=new JSONObject();response.put("Encrypt",value);response.put("MsgSignature",cipher.signature(time,nonce,value));response.put("TimeStamp",Long.parseLong(time));response.put("Nonce",nonce);
                return ResponseEntity.ok().header("Content-Type","application/json").body(response.toJSONString());
            }catch(Exception pending){return ResponseEntity.status(503).body("retry");}
        }catch(Exception error){return ResponseEntity.status(403).body("invalid");}
    }
}
