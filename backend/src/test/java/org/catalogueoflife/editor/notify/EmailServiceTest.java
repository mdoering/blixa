package org.catalogueoflife.editor.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailServiceTest {

  @SuppressWarnings("unchecked")
  private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
    ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(sender);
    return p;
  }

  private static SimpleMailMessage sent(JavaMailSender sender) {
    ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(sender).send(cap.capture());
    return cap.getValue();
  }

  @Test
  void setsCcAndReplyToWhenGiven() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "noreply@example.org")
        .send("to@example.org", "cc@example.org", "reply@example.org", "Subj", "Body");
    SimpleMailMessage m = sent(sender);
    assertThat(m.getFrom()).isEqualTo("noreply@example.org");
    assertThat(m.getTo()).containsExactly("to@example.org");
    assertThat(m.getCc()).containsExactly("cc@example.org");
    assertThat(m.getReplyTo()).isEqualTo("reply@example.org");
    assertThat(m.getSubject()).isEqualTo("Subj");
    assertThat(m.getText()).isEqualTo("Body");
  }

  @Test
  void omitsBlankCcAndReplyTo() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "noreply@example.org")
        .send("to@example.org", " ", null, "Subj", "Body");
    SimpleMailMessage m = sent(sender);
    assertThat(m.getCc()).isNull();
    assertThat(m.getReplyTo()).isNull();
  }

  @Test
  void suppressedWhenFromIsUnset() {
    JavaMailSender sender = mock(JavaMailSender.class);
    new EmailService(provider(sender), "")
        .send("to@example.org", "cc@example.org", null, "Subj", "Body");
    verifyNoInteractions(sender);
  }
}
