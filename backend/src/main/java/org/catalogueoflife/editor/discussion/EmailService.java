package org.catalogueoflife.editor.discussion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

// Best-effort outgoing email. Sends only when a JavaMailSender is configured (spring.mail.host) AND
// coldp.mail.from is set; otherwise it just logs. Never throws -- callers (notifications) treat email
// as a side effect that must not break the triggering action.
@Service
public class EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailService.class);

  private final ObjectProvider<JavaMailSender> mailSender;
  private final String from;

  public EmailService(ObjectProvider<JavaMailSender> mailSender,
      @Value("${coldp.mail.from:}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  public void send(String to, String subject, String text) {
    if (to == null || to.isBlank()) return;
    JavaMailSender sender = mailSender.getIfAvailable();
    if (sender == null || from == null || from.isBlank()) {
      log.info("email suppressed (mail not configured): to={} subject=\"{}\"", to, subject);
      return;
    }
    try {
      SimpleMailMessage msg = new SimpleMailMessage();
      msg.setFrom(from);
      msg.setTo(to);
      msg.setSubject(subject);
      msg.setText(text);
      sender.send(msg);
    } catch (Exception e) {
      log.warn("failed to send email to {}: {}", to, e.toString());
    }
  }
}
