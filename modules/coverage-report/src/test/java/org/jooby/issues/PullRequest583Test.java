package org.jooby.issues;

import java.util.concurrent.atomic.AtomicReference;

import org.jooby.test.ServerFeature;
import org.junit.Assert;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * @author Johannes Schneider (<a href="mailto:js@cedarsoft.com">js@cedarsoft.com</a>)
 */
public class PullRequest583Test extends ServerFeature {
  {

    AtomicReference<Injector> ref = new AtomicReference<>();

    injector((stage, module) -> {
      Injector injector = Guice.createInjector(module);
      ref.set(injector);
      return injector;
    });

    get("/583", () -> {
      Injector injector = require(Injector.class);
      Assert.assertSame(injector, ref.get());
      return "OK";
    });
  }

  @Test
  public void customInjector() throws Exception {
    request()
        .get("/583")
        .expect("OK");
  }
}
