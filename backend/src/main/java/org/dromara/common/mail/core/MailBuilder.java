package org.dromara.common.mail.core;

import lombok.extern.slf4j.Slf4j;

/**
 * 邮件构建器 shim: 独立开源版默认不发邮件, 仅打日志。
 * 如需真实发信, 替换为 spring-boot-starter-mail 实现即可。
 *
 * @author doubao
 */
@Slf4j
public class MailBuilder {

    private String to;
    private String subject;
    private String text;

    public static MailBuilder of() {
        return new MailBuilder();
    }

    public MailBuilder to(String to) {
        this.to = to;
        return this;
    }

    public MailBuilder subject(String subject) {
        this.subject = subject;
        return this;
    }

    public MailBuilder text(String text) {
        this.text = text;
        return this;
    }

    public void send() {
        log.info("[MailShim] 发送邮件 to={}, subject={} (独立版未接入真实 SMTP, 仅记录)", to, subject);
    }
}
