package com.ruoyi.club.service;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.club.web.ClubPhoneCodeException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.club.web.ClubUnauthorizedException;

/**
 * 小程序独立账号认证。令牌本身只返回给客户端，数据库仅保存 SHA-256 摘要。
 */
@Service
public class ClubAuthService
{
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("^[A-Za-z0-9_]{4,32}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PASSWORD_LETTER = Pattern.compile(".*[A-Za-z].*");
    private static final Pattern PASSWORD_NUMBER = Pattern.compile(".*\\d.*");
    private static final Pattern WECHAT_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,256}$");

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    @org.springframework.beans.factory.annotation.Autowired
    private ClubWechatPhoneService wechatPhone;

    @Value("${club.access-token-minutes:30}")
    private int accessTokenMinutes;

    @Value("${club.refresh-token-days:14}")
    private int refreshTokenDays;

    @Value("${club.phone-auth-mode:disabled}")
    private String phoneAuthMode;

    @Value("${club.auth-mode:wechat}")
    private String authMode;
    public boolean standalone(){return "standalone".equals(authMode);}
    public void requireStandalone(){if(!standalone())throw new ServiceException("独立账号模式未开启");}

    @Value("${club.wechat-login-mode:disabled}")
    private String wechatLoginMode;

    @Value("${club.wechat-app-id:}")
    private String wechatAppId;

    @Value("${club.wechat-app-secret:}")
    private String wechatAppSecret;

    public ClubAuthService(JdbcTemplate jdbc, BCryptPasswordEncoder passwordEncoder)
    {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> register(Map<String, Object> input)
    {
        String account = text(input.get("account")).trim();
        String password = text(input.get("password"));
        String phone = nullable(input.get("phone"));
        String email = nullable(input.get("email"));
        String nickname = text(input.get("nickname")).trim();

        if (!ACCOUNT_PATTERN.matcher(account).matches())
        {
            throw new ServiceException("账号需为4至32位字母、数字或下划线");
        }
        validatePassword(password);
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches())
        {
            throw new ServiceException("请输入正确的手机号");
        }
        if (phone != null && !standalone())
        {
            verifyPhoneCode(phone, "register", text(input.get("requestId")).trim(), text(input.get("code")).trim());
        }
        if (email != null && !EMAIL_PATTERN.matcher(email).matches())
        {
            throw new ServiceException("请输入正确的邮箱");
        }
        if (nickname.isEmpty())
        {
            nickname = account;
        }
        if (nickname.length() > 64)
        {
            throw new ServiceException("昵称不能超过64个字符");
        }
        Integer duplicate = jdbc.queryForObject(
                "select count(1) from club_user where account=? or (? is not null and phone=?) or (? is not null and email=?)",
                Integer.class, account, phone, phone, email, email);
        if (duplicate != null && duplicate > 0)
        {
            throw new ServiceException("账号、手机号或邮箱已被注册");
        }
        Long userId = insertAndKey("insert into club_user(account,phone,email,password_hash,nickname) values(?,?,?,?,?)",
                account, phone, email, passwordEncoder.encode(password), nickname);
        initializeUser(userId);
        return issueTokens(userId);
    }

    @Transactional
    public Map<String, Object> login(Map<String, Object> input)
    {
        String login = text(input.get("account")).trim();
        String password = text(input.get("password"));
        if (login.isEmpty() || password.isEmpty())
        {
            throw new ServiceException("请输入账号和密码");
        }
        List<Map<String, Object>> users = standalone()
                ? jdbc.queryForList("select * from club_user where account=? limit 1", login)
                : jdbc.queryForList("select * from club_user where account=? or phone=? or email=? limit 1", login, login, login);
        if (users.isEmpty() || !passwordEncoder.matches(password, text(users.get(0).get("password_hash"))))
        {
            throw new ServiceException("账号或密码错误");
        }
        Map<String, Object> user = users.get(0);
        if (!"active".equals(text(user.get("status"))))
        {
            throw new ServiceException("账号已被停用，请联系管理员");
        }
        Long userId = number(user.get("id"));
        jdbc.update("update club_user set last_login_at=now() where id=?", userId);
        return issueTokens(userId);
    }

    public Map<String, Object> phoneCode(Map<String, Object> input)
    {
        throw new ServiceException("验证码登录已关闭，请使用密码或微信登录");
    }

    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> phoneRegister(Map<String, Object> input)
    {
        if(standalone())throw new ServiceException("请使用独立账号注册");
        String phone = text(input.get("phone")).trim();
        String password = text(input.get("password"));
        if (!PHONE_PATTERN.matcher(phone).matches())
        {
            throw new ServiceException("请输入正确的手机号");
        }
        validatePassword(password);
        String phoneCode=text(input.get("phoneCode")).trim();
        if(phoneCode.isEmpty())throw new ServiceException("请在微信小程序授权手机号后注册，不能使用未经验证的号码");
        String verified=wechatPhone.verifiedPhone(phoneCode);
        if(!phone.equals(verified))throw new ServiceException("填写的手机号与微信授权号码不一致，请核对后重新授权");
        List<Map<String, Object>> users = jdbc.queryForList("select id,status from club_user where phone=? limit 1", phone);
        if (!users.isEmpty())
        {
            throw new ServiceException("该手机号已注册，请直接登录");
        }
        String account = "mobile_" + phone;
        Long userId = insertAndKey("insert into club_user(account,phone,password_hash,nickname,user_type,status) values(?,?,?,?,?,?)",
                account, phone, passwordEncoder.encode(password), "用户" + phone.substring(7), "user", "active");
        initializeUser(userId);
        jdbc.update("update club_user set last_login_at=now() where id=?", userId);
        return issueTokens(userId,true);
    }

    public Map<String, Object> phoneCodeLogin(Map<String, Object> input)
    {
        throw new ServiceException("验证码登录已关闭，请使用密码或微信登录");
    }

    /** 使用 wx.login 的临时 code 换取 openid，AppSecret 只存在于服务端环境变量。 */
    @Transactional
    public Map<String, Object> wechatLogin(Map<String, Object> input)
    {
        if (!"real".equalsIgnoreCase(wechatLoginMode)) throw new ServiceException("真实微信登录尚未开启");
        if (wechatAppId.trim().isEmpty() || wechatAppSecret.trim().isEmpty()) throw new ServiceException("微信登录服务器配置不完整");
        String code = text(input.get("code")).trim();
        if (!WECHAT_CODE_PATTERN.matcher(code).matches()) throw new ServiceException("微信登录凭证无效，请重新进入小程序");
        Map<String, Object> wechat = exchangeWechatCode(code);
        String openid = text(wechat.get("openid")).trim();
        String unionid = text(wechat.get("unionid")).trim();
        String sessionKey = text(wechat.get("session_key"));
        if (openid.isEmpty() || sessionKey.isEmpty()) throw new ServiceException("微信登录未返回有效用户身份");
        List<Map<String, Object>> users = jdbc.queryForList(
                "select w.user_id as id,u.status from club_wechat_identity w join club_user u on u.id=w.user_id " +
                "where w.openid=? or (?<>'' and w.unionid=?) limit 1 for update", openid, unionid, unionid);
        Long userId;
        if (users.isEmpty())
        {
            String identitySeed = unionid.isEmpty() ? openid : unionid;
            String account = "wx_" + sha256(identitySeed).substring(0, 24);
            jdbc.update("insert ignore into club_user(account,password_hash,nickname,user_type,status) values(?,?,?,?,?)",
                    account, passwordEncoder.encode(randomToken()), "微信用户", "user", "active");
            userId = jdbc.queryForObject("select id from club_user where account=?", Long.class, account);
            initializeUser(userId);
            jdbc.update("insert ignore into club_wechat_identity(user_id,openid,unionid,session_key_hash,last_login_at) values(?,?,?,?,now())",
                    userId, openid, unionid.isEmpty() ? null : unionid, sha256(sessionKey));
            List<Map<String, Object>> bound = jdbc.queryForList(
                    "select user_id from club_wechat_identity where openid=? or (?<>'' and unionid=?) limit 1 for update",
                    openid, unionid, unionid);
            if (bound.isEmpty()) throw new ServiceException("微信身份绑定失败，请稍后重试");
            userId = number(bound.get(0).get("user_id"));
        }
        else
        {
            if (!"active".equals(text(users.get(0).get("status"))))
            {
                throw new ServiceException("账号已被停用，请联系管理员");
            }
            userId = number(users.get(0).get("id"));
            jdbc.update("update club_wechat_identity set session_key_hash=?,last_login_at=now(),unionid=case when ?='' then unionid else ? end where user_id=?",
                    sha256(sessionKey), unionid, unionid, userId);
        }
        jdbc.update("update club_user set last_login_at=now() where id=?", userId);
        return issueTokens(userId);
    }

    @Transactional
    public Map<String,Object> setContactPhone(Long userId,Map<String,Object> input) {
        requireStandalone();
        String phone=text(input.get("phone")).trim();
        if(!PHONE_PATTERN.matcher(phone).matches())throw new ServiceException("请输入正确的联系手机号");
        String hash=jdbc.queryForObject("select password_hash from club_user where id=? for update",String.class,userId);
        if(!passwordEncoder.matches(text(input.get("password")),hash))throw new ServiceException("请填写正确的当前密码");
        try { jdbc.update("update club_user set phone=? where id=?",phone,userId); }
        catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("该联系手机号已用于其他账号，请核对或联系客服");}
        return bindingState(userId);
    }

    public Map<String, Object> bindingState(Long userId)
    {
        Map<String, Object> result = new HashMap<>(userView(userId));
        Integer wechat = jdbc.queryForObject("select count(1) from club_wechat_identity where user_id=?", Integer.class, userId);
        result.put("wechatBound", wechat != null && wechat == 1);
        result.put("phoneBound", !text(result.get("phone")).trim().isEmpty());
        result.put("authMode",standalone()?"standalone":"wechat");
        result.put("canUnbindWechat", wechat != null && wechat == 1 && !text(result.get("phone")).trim().isEmpty());
        result.put("canUnbindPhone", wechat != null && wechat == 1 && !text(result.get("phone")).trim().isEmpty());
        result.put("pendingMerges", jdbc.queryForList("select request_no as requestNo,conflict_type as conflictType,preview_json as previewJson,expires_at as expiresAt from club_account_merge_request where target_user_id=? and status='previewed' and expires_at>now() order by id desc", userId));
        return result;
    }

    @Transactional
    public Map<String, Object> bindWechat(Long userId, Map<String, Object> input)
    {
        requireWechatConfig();
        String code = text(input.get("code")).trim();
        if (!WECHAT_CODE_PATTERN.matcher(code).matches()) throw new ServiceException("微信登录凭证无效，请重试");
        Map<String, Object> wechat = exchangeWechatCode(code);
        String openid = text(wechat.get("openid")).trim();
        String unionid = text(wechat.get("unionid")).trim();
        String sessionKey = text(wechat.get("session_key"));
        if (openid.isEmpty() || sessionKey.isEmpty()) throw new ServiceException("微信登录未返回有效用户身份");
        List<Map<String, Object>> mine = jdbc.queryForList("select * from club_wechat_identity where user_id=? for update", userId);
        if (!mine.isEmpty())
        {
            if (!openid.equals(text(mine.get(0).get("openid")))) throw new ServiceException("当前账号已绑定其他微信，不能直接覆盖");
            jdbc.update("update club_wechat_identity set session_key_hash=?,last_login_at=now() where user_id=?", sha256(sessionKey), userId);
            return bindingState(userId);
        }
        List<Map<String, Object>> owner = jdbc.queryForList("select user_id from club_wechat_identity where openid=? or (?<>'' and unionid=?) limit 1 for update", openid, unionid, unionid);
        if (!owner.isEmpty() && !Objects.equals(number(owner.get(0).get("user_id")), userId))
            return createMergePreview(userId, number(owner.get(0).get("user_id")), "wechat");
        jdbc.update("insert into club_wechat_identity(user_id,openid,unionid,session_key_hash,last_login_at) values(?,?,?,?,now())", userId, openid, unionid.isEmpty() ? null : unionid, sha256(sessionKey));
        return bindingState(userId);
    }

    /** Promotes a limited password session only after the current WeChat has been verified. */
    @Transactional
    public Map<String,Object> bindWechatSession(Long userId,Map<String,Object> input)
    {
        Map<String,Object> result=bindWechat(userId,input);
        if(Boolean.TRUE.equals(result.get("wechatBound")) && !Boolean.TRUE.equals(result.get("mergeRequired")))
            result.putAll(issueTokens(userId));
        return result;
    }

    /** Unbinds exactly one login identity after proof, while preserving at least one usable login method. */
    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> unbind(Long userId, Map<String, Object> input)
    {
        String type = text(input.get("type")).trim().toLowerCase();
        List<Map<String, Object>> users = jdbc.queryForList("select phone,password_hash from club_user where id=? for update", userId);
        if (users.isEmpty()) throw new ServiceException("用户不存在");
        Map<String, Object> user = users.get(0);
        Integer wechatCount = jdbc.queryForObject("select count(1) from club_wechat_identity where user_id=?", Integer.class, userId);
        boolean hasWechat = wechatCount != null && wechatCount > 0;
        String phone = text(user.get("phone")).trim();
        if ("phone".equals(type))
        {
            requirePhoneVerification( "手机号解绑");
            if (phone.isEmpty()) throw new ServiceException("当前账号未绑定手机号");
            if (!hasWechat) throw new ServiceException("解绑后将没有可用登录方式，请先绑定微信");
            verifyPhoneCode(phone, "unbind", text(input.get("requestId")).trim(), text(input.get("code")).trim());
            jdbc.update("update club_user set phone=null where id=?", userId);
        }
        else if ("wechat".equals(type))
        {
            if (!hasWechat) throw new ServiceException("当前账号未绑定微信");
            if (phone.isEmpty()) throw new ServiceException("解绑后将没有可用登录方式，请先绑定手机号");
            requirePhoneVerification( "微信解绑验证");
            verifyPhoneCode(phone, "unbind", text(input.get("requestId")).trim(), text(input.get("code")).trim());
            jdbc.update("delete from club_wechat_identity where user_id=?", userId);
        }
        else throw new ServiceException("解绑类型不正确");
        jdbc.update("update club_user_token set revoked_at=now() where user_id=? and token_type in ('refresh','bind_refresh') and revoked_at is null", userId);
        return bindingState(userId);
    }

    @Transactional
    public Map<String,Object> bindWechatPhone(Long userId,Map<String,Object> input)
    {
        // User id comes exclusively from the authenticated session. No merge or UID change.
        String phone=wechatPhone.verifiedPhone(text(input.get("phoneCode")).trim());
        List<Map<String,Object>> mine=jdbc.queryForList("select phone,status from club_user where id=? for update",userId);
        if(mine.isEmpty()||!"active".equals(text(mine.get(0).get("status"))))throw new ServiceException("当前账号不可用");
        String current=text(mine.get(0).get("phone")).trim();
        if(!current.isEmpty()&&!current.equals(phone))throw new ServiceException("当前账号已绑定其他手机号，如需更换请联系客服核验");
        List<Map<String,Object>> owner=jdbc.queryForList("select id from club_user where phone=? and id<>? limit 1 for update",phone,userId);
        if(!owner.isEmpty())throw new ServiceException("该手机号已绑定其他账号，请联系客服处理；当前 UID 和余额不变");
        try{jdbc.update("update club_user set phone=? where id=?",phone,userId);}
        catch(org.springframework.dao.DuplicateKeyException e){throw new ServiceException("该手机号已绑定其他账号，请联系客服处理");}
        return bindingState(userId);
    }

    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> bindPhone(Long userId, Map<String, Object> input)
    {
        requirePhoneVerification( "手机号绑定");
        String phone = text(input.get("phone")).trim();
        if (!PHONE_PATTERN.matcher(phone).matches()) throw new ServiceException("请输入正确的手机号");
        verifyPhoneCode(phone, "bind", text(input.get("requestId")).trim(), text(input.get("code")).trim());
        List<Map<String, Object>> owner = jdbc.queryForList("select id from club_user where phone=? limit 1 for update", phone);
        if (!owner.isEmpty() && !Objects.equals(number(owner.get(0).get("id")), userId))
            return createMergePreview(userId, number(owner.get(0).get("id")), "phone");
        List<Map<String, Object>> mine = jdbc.queryForList("select phone from club_user where id=? for update", userId);
        if (mine.isEmpty()) throw new ServiceException("用户不存在");
        String current = text(mine.get(0).get("phone")).trim();
        if (!current.isEmpty() && !current.equals(phone)) throw new ServiceException("当前账号已绑定其他手机号，请先完成身份核验");
        jdbc.update("update club_user set phone=? where id=?", phone, userId);
        return bindingState(userId);
    }

    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> resetPassword(Long userId, Map<String, Object> input)
    {
        String password = text(input.get("password"));
        validatePassword(password);
        String wechatCode = text(input.get("wechatCode")).trim();
        if (!wechatCode.isEmpty())
        {
            requireWechatConfig();
            if (!WECHAT_CODE_PATTERN.matcher(wechatCode).matches()) throw new ServiceException("微信登录凭证无效，请重试");
            Map<String, Object> identity = exchangeWechatCode(wechatCode);
            String openid = text(identity.get("openid")).trim();
            List<String> bound = jdbc.queryForList("select openid from club_wechat_identity where user_id=?", String.class, userId);
            if (openid.isEmpty() || !bound.contains(openid)) throw new ServiceException("请使用当前账号已绑定的微信验证");
        }
        else
        {
            String oldPassword = text(input.get("oldPassword"));
            String hash = jdbc.queryForObject("select password_hash from club_user where id=?", String.class, userId);
            if (oldPassword.isEmpty() || hash == null || !passwordEncoder.matches(oldPassword, hash))
                throw new ServiceException("原密码不正确，请重新输入或使用绑定微信验证");
        }
        jdbc.update("update club_user set password_hash=? where id=?", passwordEncoder.encode(password), userId);
        jdbc.update("update club_user_token set revoked_at=now() where user_id=? and revoked_at is null", userId);
        return issueTokens(userId);
    }

    @Transactional(noRollbackFor = ClubPhoneCodeException.class)
    public Map<String, Object> mergeAccounts(Long userId, Map<String, Object> input)
    {
        String requestNo = text(input.get("requestNo")).trim();
        if (!Boolean.TRUE.equals(input.get("confirm"))) throw new ServiceException("合并前必须明确确认影响范围");
        List<Map<String, Object>> requests = jdbc.queryForList("select * from club_account_merge_request where request_no=? and target_user_id=? and status='previewed' and expires_at>now() for update", requestNo, userId);
        if (requests.isEmpty()) throw new ServiceException("账号合并请求不存在或已过期");
        Map<String, Object> request = requests.get(0);
        Long sourceId = number(request.get("source_user_id"));
        String phone = text(input.get("phone")).trim();
        List<Map<String, Object>> phones = jdbc.queryForList("select phone from club_user where id in (?,?) and phone=?", userId, sourceId, phone);
        if (phones.isEmpty()) throw new ServiceException("请使用待合并账号中已绑定的手机号验证");
        verifyPhoneCode(phone, "merge", text(input.get("phoneRequestId")).trim(), text(input.get("phoneCode")).trim());
        lockMergeScope(userId, sourceId);
        assertMergeSafe(userId, sourceId);
        mergeBusinessData(userId, sourceId, requestNo);
        if ("phone".equals(text(request.get("conflict_type"))))
            jdbc.update("update club_user set phone=? where id=?", phone, userId);
        jdbc.update("update club_account_merge_request set status='merged',verified_at=now(),merged_at=now() where id=?", request.get("id"));
        jdbc.update("update club_user_token set revoked_at=now() where user_id in (?,?) and revoked_at is null", userId, sourceId);
        return issueTokens(userId);
    }

    private void requireWechatConfig()
    {
        if (!"real".equalsIgnoreCase(wechatLoginMode)) throw new ServiceException("真实微信登录尚未开启");
        if (wechatAppId.trim().isEmpty() || wechatAppSecret.trim().isEmpty()) throw new ServiceException("微信登录服务器配置不完整");
    }

    private Map<String, Object> createMergePreview(Long targetId, Long sourceId, String conflictType)
    {
        List<Map<String, Object>> existing = jdbc.queryForList("select request_no,preview_json,expires_at from club_account_merge_request where target_user_id=? and source_user_id=? and conflict_type=? and status='previewed' and expires_at>now() order by id desc limit 1", targetId, sourceId, conflictType);
        Map<String, Object> preview = mergeImpact(targetId, sourceId);
        String requestNo;
        if (existing.isEmpty())
        {
            requestNo = "MRG" + System.currentTimeMillis() + randomDigits();
            jdbc.update("insert into club_account_merge_request(request_no,target_user_id,source_user_id,conflict_type,preview_json,expires_at) values(?,?,?,?,?,date_add(now(),interval 15 minute))", requestNo, targetId, sourceId, conflictType, JSON.toJSONString(preview));
        }
        else requestNo = text(existing.get(0).get("request_no"));
        Map<String, Object> result = new HashMap<>();
        result.put("mergeRequired", true);
        result.put("requestNo", requestNo);
        result.put("authMode",standalone()?"standalone":"wechat");
        result.put("expiresIn", 900);
        result.put("impact", preview);
        result.put("message", "该身份已绑定其他账号，请核对影响范围并再次验证后合并");
        return result;
    }

    private Map<String, Object> mergeImpact(Long targetId, Long sourceId)
    {
        Map<String, Object> impact = new HashMap<>();
        impact.put("targetUserId", targetId); impact.put("sourceUserId", sourceId);
        impact.put("orders", count("club_order", sourceId)); impact.put("walletRecords", count("club_wallet_record", sourceId));
        impact.put("payments", count("club_payment", sourceId)); impact.put("aftersales", count("club_aftersale", sourceId));
        impact.put("coupons", count("club_coupon_issue", sourceId)); impact.put("favorites", count("club_favorite", sourceId));
        impact.put("follows", count("club_follow", sourceId)); impact.put("applications", count("club_application", sourceId));
        impact.put("messages", count("club_message", sourceId));
        impact.put("wallets", jdbc.queryForList("select user_id,balance,frozen,total_income,total_withdrawn from club_wallet where user_id in (?,?) order by user_id", targetId, sourceId));
        return impact;
    }

    private int count(String table, Long userId)
    {
        Integer count = jdbc.queryForObject("select count(1) from " + table + " where user_id=?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private void assertMergeSafe(Long targetId, Long sourceId)
    {
        Integer identities = jdbc.queryForObject("select count(1) from club_identity where user_id in (?,?)", Integer.class, targetId, sourceId);
        if (identities != null && identities > 1) throw new ServiceException("两个账号都存在实名记录，需要平台人工核验后合并");
        Integer profiles = jdbc.queryForObject("select (select count(1) from club_player_profile where user_id in (?,?))+(select count(1) from club_shop where owner_user_id in (?,?))", Integer.class, targetId, sourceId, targetId, sourceId);
        if (profiles != null && profiles > 1) throw new ServiceException("待合并账号包含多个业务身份，需要平台确认订单和结算归属");
        Integer wechat = jdbc.queryForObject("select count(1) from club_wechat_identity where user_id in (?,?)", Integer.class, targetId, sourceId);
        if (wechat != null && wechat > 1) throw new ServiceException("两个账号均已绑定微信，不能自动合并");
        Integer teenSettings = jdbc.queryForObject("select count(1) from club_teen_setting where user_id in (?,?)", Integer.class, targetId, sourceId);
        if (teenSettings != null && teenSettings > 1) throw new ServiceException("两个账号均设置了青少年监护密码，需先人工核验");
    }

    private void mergeBusinessData(Long targetId, Long sourceId, String requestNo)
    {
        List<Map<String, Object>> wallets = jdbc.queryForList("select * from club_wallet where user_id in (?,?) for update", targetId, sourceId);
        BigDecimal targetBalance = BigDecimal.ZERO, sourceBalance = BigDecimal.ZERO, targetFrozen = BigDecimal.ZERO, sourceFrozen = BigDecimal.ZERO;
        BigDecimal sourceIncome = BigDecimal.ZERO, sourceWithdrawn = BigDecimal.ZERO;
        for (Map<String, Object> wallet : wallets)
        {
            if (Objects.equals(number(wallet.get("user_id")), targetId)) { targetBalance=money(wallet.get("balance")); targetFrozen=money(wallet.get("frozen")); }
            else { sourceBalance=money(wallet.get("balance")); sourceFrozen=money(wallet.get("frozen")); sourceIncome=money(wallet.get("total_income")); sourceWithdrawn=money(wallet.get("total_withdrawn")); }
        }
        jdbc.update("update club_wallet set balance=balance+?,frozen=frozen+?,total_income=total_income+?,total_withdrawn=total_withdrawn+?,version=version+1 where user_id=?", sourceBalance, sourceFrozen, sourceIncome, sourceWithdrawn, targetId);
        jdbc.update("update club_wallet set balance=0,frozen=0,total_income=0,total_withdrawn=0,version=version+1 where user_id=?", sourceId);
        jdbc.update("insert into club_wallet_record(user_id,record_type,amount,balance_before,balance_after,frozen_after,reference_no,counterparty_type,description) values(?,?,?,?,?,?,?,?,?)", targetId, "account_merge", sourceBalance, targetBalance, targetBalance.add(sourceBalance), targetFrozen.add(sourceFrozen), requestNo, "account_merge", "受控账号合并资金转入");
        jdbc.update("update club_order set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_payment set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_payment_audit set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("insert ignore into club_action_request(user_id,business_type,business_id,action,idempotency_key,created_at) " +
                "select ?,business_type,business_id,action,idempotency_key,created_at from club_action_request where user_id=?", targetId, sourceId);
        jdbc.update("delete from club_action_request where user_id=?", sourceId);
        jdbc.update("update club_recharge_order set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_withdrawal set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_wallet_record set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_message set user_id=? where user_id=?", targetId, sourceId);
        List<Map<String, Object>> sourceNotifications = jdbc.queryForList(
                "select order_enabled,promotion_enabled,system_enabled from club_notification_setting where user_id=?", sourceId);
        if (!sourceNotifications.isEmpty())
        {
            Map<String, Object> sourceNotification = sourceNotifications.get(0);
            jdbc.update("insert ignore into club_notification_setting(user_id,order_enabled,promotion_enabled,system_enabled) values(?,?,?,?)",
                    targetId, sourceNotification.get("order_enabled"), sourceNotification.get("promotion_enabled"), sourceNotification.get("system_enabled"));
            jdbc.update("update club_notification_setting set order_enabled=1,promotion_enabled=least(promotion_enabled,?),system_enabled=1 where user_id=?",
                    sourceNotification.get("promotion_enabled"), targetId);
        }
        jdbc.update("delete from club_notification_setting where user_id=?", sourceId);
        jdbc.update("update club_teen_setting set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_poster set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_review set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_coupon_issue set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("insert ignore into club_user_coupon(user_id,coupon_id,status,used_order_id,created_at,used_at) select ?,coupon_id,status,used_order_id,created_at,used_at from club_user_coupon where user_id=?", targetId, sourceId);
        jdbc.update("delete from club_user_coupon where user_id=?", sourceId);
        jdbc.update("update club_application set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("insert ignore into club_favorite(user_id,product_id,created_at) select ?,product_id,created_at from club_favorite where user_id=?", targetId, sourceId);
        jdbc.update("delete from club_favorite where user_id=?", sourceId);
        jdbc.update("insert ignore into club_follow(user_id,shop_id,created_at) select ?,shop_id,created_at from club_follow where user_id=?", targetId, sourceId);
        jdbc.update("delete from club_follow where user_id=?", sourceId);
        jdbc.update("update club_identity set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_player_profile set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_shop set owner_user_id=? where owner_user_id=?", targetId, sourceId);
        jdbc.update("update club_order set provider_user_id=? where provider_user_id=?", targetId, sourceId);
        jdbc.update("update club_order_settlement set provider_user_id=? where provider_user_id=?", targetId, sourceId);
        jdbc.update("update club_aftersale set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("update club_aftersale set provider_user_id=? where provider_user_id=?", targetId, sourceId);
        jdbc.update("update club_provider_receivable set provider_user_id=? where provider_user_id=?", targetId, sourceId);
        jdbc.update("update club_wechat_identity set user_id=? where user_id=?", targetId, sourceId);
        jdbc.update("insert into club_role_status(user_id,role_type,service_status) select ?,role_type,service_status from club_role_status where user_id=? on duplicate key update role_type=values(role_type),service_status=values(service_status)", targetId, sourceId);
        jdbc.update("delete from club_role_status where user_id=?", sourceId);
        jdbc.update("update club_user t join club_user s on s.id=? set t.nickname=case when t.nickname like '微信用户%' then s.nickname else t.nickname end,t.phone=coalesce(t.phone,s.phone),t.email=coalesce(t.email,s.email),t.user_type=case when t.user_type='user' then s.user_type else t.user_type end where t.id=?", sourceId, targetId);
        jdbc.update("update club_user set status='merged',phone=null,email=null where id=?", sourceId);
    }

    /** Locks every financial and identity aggregate before account ownership is moved. */
    private void lockMergeScope(Long targetId, Long sourceId)
    {
        jdbc.queryForList("select id from club_user where id in (?,?) order by id for update", targetId, sourceId);
        jdbc.queryForList("select user_id from club_wallet where user_id in (?,?) order by user_id for update", targetId, sourceId);
        jdbc.queryForList("select id from club_withdrawal where user_id in (?,?) order by id for update", targetId, sourceId);
        jdbc.queryForList("select id from club_order where user_id in (?,?) or provider_user_id in (?,?) order by id for update",
                targetId, sourceId, targetId, sourceId);
        jdbc.queryForList("select id from club_order_settlement where provider_user_id in (?,?) order by id for update", targetId, sourceId);
        jdbc.queryForList("select id from club_provider_receivable where provider_user_id in (?,?) order by id for update", targetId, sourceId);
        jdbc.queryForList("select id from club_wechat_identity where user_id in (?,?) order by id for update", targetId, sourceId);
    }

    private void verifyPhoneCode(String phone, String purpose, String requestId, String code)
    {
        throw new ClubPhoneCodeException("短信验证服务未接入，请使用已绑定微信核验或联系客服");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeWechatCode(String code)
    {
        try
        {
            URI uri = UriComponentsBuilder.fromHttpUrl("https://api.weixin.qq.com/sns/jscode2session")
                    .queryParam("appid", wechatAppId).queryParam("secret", wechatAppSecret)
                    .queryParam("js_code", code).queryParam("grant_type", "authorization_code")
                    .build().encode().toUri();
            String body = new RestTemplate().getForObject(uri, String.class);
            Map<String, Object> result = JSON.parseObject(body, Map.class);
            if (result == null) throw new ServiceException("微信登录服务返回异常");
            if (result.get("errcode") != null && number(result.get("errcode")) != 0L)
                throw new ServiceException("微信登录失败，请重新进入小程序后再试");
            return result;
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("微信登录服务暂时不可用，请稍后重试");
        }
    }

    @Transactional
    public Map<String, Object> refresh(Map<String, Object> input)
    {
        String refreshToken = text(input.get("refreshToken"));
        if (refreshToken.isEmpty())
        {
            throw new ServiceException("缺少刷新令牌");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.id,t.user_id,t.token_type,u.status from club_user_token t join club_user u on u.id=t.user_id " +
                "where t.token_hash=? and t.token_type in ('refresh','bind_refresh') and t.revoked_at is null and t.expires_at>now() limit 1",
                sha256(refreshToken));
        if (rows.isEmpty() || !"active".equals(text(rows.get(0).get("status"))))
        {
            throw new ServiceException("刷新令牌已失效，请重新登录");
        }
        Long userId = number(rows.get(0).get("user_id"));
        jdbc.update("update club_user_token set revoked_at=now() where id=?", rows.get(0).get("id"));
        return issueTokens(userId,"bind_refresh".equals(text(rows.get(0).get("token_type"))));
    }

    @Transactional
    public void logout(HttpServletRequest request)
    {
        String token = bearer(request);
        if (!token.isEmpty())
        {
            List<Map<String, Object>> rows = jdbc.queryForList("select user_id from club_user_token where token_hash=?", sha256(token));
            jdbc.update("update club_user_token set revoked_at=now() where token_hash=? and revoked_at is null", sha256(token));
            if (!rows.isEmpty())
            {
                jdbc.update("update club_user_token set revoked_at=now() where user_id=? and token_type in ('refresh','bind_refresh') and revoked_at is null",
                        rows.get(0).get("user_id"));
            }
        }
    }

    /** Ordinary business access requires a full account session; financial WeChat operations require binding separately. */
    public Long requireUserId(HttpServletRequest request)
    {
        Long userId=requireAuthenticatedUserId(request);
        String kind=jdbc.queryForObject("select token_type from club_user_token where token_hash=?",String.class,sha256(bearer(request)));
        if(!"access".equals(kind))throw new com.ruoyi.club.web.ClubWechatBindingRequiredException();
        return userId;
    }

    /** A verified contact number is required at checkout, not for browsing. */
    public Long requireContactUserId(HttpServletRequest request)
    {
        Long userId=requireUserId(request);
        if(standalone())return userId;
        String phone=jdbc.queryForObject("select phone from club_user where id=?",String.class,userId);
        if(phone==null || phone.trim().isEmpty())throw new com.ruoyi.club.web.ClubPhoneBindingRequiredException();
        return userId;
    }

    /** Phone binding must remain reachable before a phone has been bound. */
    public Long requireWechatUserId(HttpServletRequest request)
    {
        Long userId=requireAuthenticatedUserId(request);
        String kind=jdbc.queryForObject("select token_type from club_user_token where token_hash=?",String.class,sha256(bearer(request)));
        if(!"access".equals(kind))throw new com.ruoyi.club.web.ClubWechatBindingRequiredException();
        if(!standalone())assertWechatBound(userId);
        return userId;
    }

    public void assertWechatBound(Long userId)
    {
        Integer count=jdbc.queryForObject("select count(1) from club_wechat_identity where user_id=?",Integer.class,userId);
        if(count==null || count<1)throw new com.ruoyi.club.web.ClubWechatBindingRequiredException();
    }

    /** Only binding-state, bind-WeChat and logout endpoints may use this limited identity. */
    public Long requireAuthenticatedUserId(HttpServletRequest request)
    {
        String token = bearer(request);
        if (token.isEmpty())
        {
            throw new ClubUnauthorizedException("请先登录");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.user_id from club_user_token t join club_user u on u.id=t.user_id " +
                "where t.token_hash=? and t.token_type in ('access','binding') and t.revoked_at is null and t.expires_at>now() and u.status='active' limit 1",
                sha256(token));
        if (rows.isEmpty())
        {
            throw new ClubUnauthorizedException("登录状态已过期，请重新登录");
        }
        return number(rows.get(0).get("user_id"));
    }

    /** Resolve an app user for public endpoints without turning anonymous access into a login requirement. */
    public Long optionalUserId(HttpServletRequest request)
    {
        String token = bearer(request);
        if (token.isEmpty()) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.user_id from club_user_token t join club_user u on u.id=t.user_id " +
                "where t.token_hash=? and t.token_type='access' and t.revoked_at is null and t.expires_at>now() and u.status='active' limit 1",
                sha256(token));
        if (rows.isEmpty()) throw new ClubUnauthorizedException("登录状态已过期，请重新登录");
        return number(rows.get(0).get("user_id"));
    }

    public Map<String, Object> userView(Long userId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,account,phone,email,nickname,avatar,gender,birthday,city,level_name as level,experience,user_type as userType,status,created_at as createdAt " +
                "from club_user where id=?", userId);
        if (rows.isEmpty())
        {
            throw new ServiceException("用户不存在");
        }
        return rows.get(0);
    }

    private void initializeUser(Long userId)
    {
        jdbc.update("insert ignore into club_wallet(user_id,balance,frozen,total_income,total_withdrawn) values(?,0,0,0,0)", userId);
        jdbc.update("insert ignore into club_notification_setting(user_id) values(?)", userId);
        // Coupon issuance is controlled by explicit admin/public/recharge rules.
    }

    private void issueWelcomeCoupon(Long userId)
    {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,total_count,issued_count,per_user_limit from club_coupon where status='active' and now() between valid_from and valid_until and issued_count<total_count order by id limit 1 for update");
        if (rows.isEmpty()) return;
        Map<String, Object> coupon = rows.get(0);
        Long couponId = number(coupon.get("id"));
        Integer owned = jdbc.queryForObject("select coalesce(sum(quantity),0) from club_coupon_issue where user_id=? and coupon_id=?", Integer.class, userId, couponId);
        int limit = coupon.get("per_user_limit") == null ? 1 : Integer.parseInt(String.valueOf(coupon.get("per_user_limit")));
        if (owned != null && owned >= limit) return;
        String key = "register:" + userId + ":" + couponId;
        int inserted = jdbc.update("insert ignore into club_coupon_issue(user_id,coupon_id,quantity,remaining_count,source,idempotency_key,status) values(?,?,1,1,'register',?,'active')", userId, couponId, key);
        if (inserted == 1)
        {
            jdbc.update("update club_coupon set issued_count=issued_count+1 where id=? and issued_count<total_count", couponId);
        }
    }

    private void requirePhoneVerification(String feature)
    {
        throw new ServiceException(feature + "需要身份核验，请联系客服");
    }

    private Map<String,Object> issueTokens(Long userId) { return issueTokens(userId,false); }

    private Map<String, Object> issueTokens(Long userId,boolean bindingOnly)
    {
        if(standalone())bindingOnly=false;
        jdbc.update("update club_user_token set revoked_at=now() where user_id=? and revoked_at is null", userId);
        String access = randomToken();
        String refresh = randomToken();
        LocalDateTime accessExpiry = LocalDateTime.now().plusMinutes(bindingOnly?10:accessTokenMinutes);
        LocalDateTime refreshExpiry = bindingOnly?LocalDateTime.now().plusMinutes(10):LocalDateTime.now().plusDays(refreshTokenDays);
        jdbc.update("insert into club_user_token(user_id,token_type,token_hash,expires_at) values(?,?,?,?)",
                userId, bindingOnly?"binding":"access", sha256(access), Timestamp.valueOf(accessExpiry));
        jdbc.update("insert into club_user_token(user_id,token_type,token_hash,expires_at) values(?,?,?,?)",
                userId, bindingOnly?"bind_refresh":"refresh", sha256(refresh), Timestamp.valueOf(refreshExpiry));
        Map<String, Object> result = new HashMap<>();
        result.put("accessToken", access);
        result.put("refreshToken", refresh);
        result.put("expiresIn", (bindingOnly?10:accessTokenMinutes) * 60);
        result.put("authMode", standalone()?"standalone":"wechat");
        result.put("wechatBindingRequired", bindingOnly);
        result.put("user", userView(userId));
        result.put("phoneBound",!text(((Map<?,?>)result.get("user")).get("phone")).trim().isEmpty());
        Integer bound=jdbc.queryForObject("select count(1) from club_wechat_identity where user_id=?",Integer.class,userId);
        result.put("wechatBound",!bindingOnly&&bound!=null&&bound>0);
        return result;
    }

    private void validatePassword(String password)
    {
        if (password.length() < 8 || password.length() > 64 ||
                !PASSWORD_LETTER.matcher(password).matches() || !PASSWORD_NUMBER.matcher(password).matches())
        {
            throw new ServiceException("密码需为8至64位，并同时包含字母和数字");
        }
    }

    private Long insertAndKey(String sql, Object... args)
    {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int updated = jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (updated != 1 || key == null) throw new ServiceException("创建账号失败，请稍后重试");
        return key.longValue();
    }

    private String randomToken()
    {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomDigits()
    {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    public String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : digest)
            {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("令牌摘要生成失败", e);
        }
    }

    private String bearer(HttpServletRequest request)
    {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
    }

    private static String text(Object value)
    {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullable(Object value)
    {
        String result = text(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static Long number(Object value)
    {
        return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(String.valueOf(value));
    }

    private static BigDecimal money(Object value)
    {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
