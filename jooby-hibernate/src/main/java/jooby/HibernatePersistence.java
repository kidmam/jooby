package jooby;

import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import javax.inject.Provider;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.SharedCacheMode;
import javax.persistence.ValidationMode;
import javax.persistence.spi.PersistenceUnitTransactionType;
import javax.sql.DataSource;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.jpa.AvailableSettings;
import org.hibernate.jpa.HibernateEntityManagerFactory;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.jpa.boot.spi.Bootstrap;
import org.hibernate.jpa.boot.spi.EntityManagerFactoryBuilder;
import org.hibernate.jpa.boot.spi.PersistenceUnitDescriptor;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.multibindings.Multibinder;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class HibernatePersistence extends JDBC {

  private final List<Class<?>> classes = new LinkedList<>();

  private boolean scan = false;

  private HibernateEntityManagerFactory emf;

  public HibernatePersistence(final String name, final Class<?>... classes) {
    super(name);
    this.classes.addAll(Arrays.asList(classes));
  }

  public HibernatePersistence(final Class<?>... classes) {
    this.classes.addAll(Arrays.asList(classes));
  }

  public HibernatePersistence scan() {
    this.scan = true;
    return this;
  }

  @Override
  public Config config() {
    Config jdbc = super.config();
    return ConfigFactory.parseResources("hibernate.conf").withFallback(jdbc);
  }

  @Override
  public void configure(final Mode mode, final Config config, final Binder binder)
      throws Exception {
    super.configure(mode, config, binder);

    EntityManagerFactoryBuilder builder = Bootstrap
        .getEntityManagerFactoryBuilder(descriptor(dataSource(), config, scan),
            config(config, classes));

    HibernateEntityManagerFactory emf = (HibernateEntityManagerFactory) builder.build();

    binder.bind(EntityManagerFactory.class).toInstance(emf);

    Key<EntityManager> key = dataSourceKey(EntityManager.class);

    Multibinder.newSetBinder(binder, RouteInterceptor.class).addBinding()
        .toInstance(new RouteInterceptor() {

          @Override
          public void before(final Request request, final Response response) throws Exception {
            EntityManager em = request.getInstance(key);
            Session session = (Session) em.getDelegate();
            session.setFlushMode(FlushMode.AUTO);
            EntityTransaction trx = em.getTransaction();
            trx.begin();
          }

          @Override
          public void beforeSend(final Request request, final Response response) throws Exception {
            EntityManager em = request.getInstance(key);
            // commit the transaction, but keep the EM open
            EntityTransaction trx = em.getTransaction();
            if (trx.isActive()) {
              trx.commit();

              // change flush mode to MANUAL (a.k.a READ-ONLY)
              Session session = (Session) em.getDelegate();
              session.setFlushMode(FlushMode.MANUAL);
              EntityTransaction readONLY = em.getTransaction();
              readONLY.begin();
            }
          }

          @Override
          public void after(final Request request, final Response response, final Exception ex)
              throws Exception {
            EntityManager em = request.getInstance(key);
            try {
              // rollback trx
              EntityTransaction trx = em.getTransaction();
              if (trx.isActive()) {
                trx.rollback();
              }
            } finally {
              em.close();
            }
          }

          @Override
          public void after(final Request request, final Response response) throws Exception {
            EntityManager em = request.getInstance(key);
            try {
              // commit trx
              EntityTransaction trx = em.getTransaction();
              if (trx.isActive()) {
                trx.commit();
              }
            } finally {
              em.close();
            }

          }
        });

    Multibinder.newSetBinder(binder, RequestModule.class).addBinding().toInstance((b) -> {
      EntityManager em = emf.createEntityManager();
      b.bind(key).toInstance(em);
    });

    // keep emf
    this.emf = emf;
  }

  @Override
  public void stop() throws Exception {
    emf.close();
    super.stop();
  }

  private static PersistenceUnitDescriptor descriptor(final Provider<DataSource> dataSourceHolder,
      final Config config, final boolean scan) {
    return new PersistenceUnitDescriptor() {
      @SuppressWarnings("unchecked")
      private <T> T property(final String name, final T defaultValue) {
        if (config.hasPath(name)) {
          return (T) config.getAnyRef(name);
        }
        return defaultValue;
      }

      @Override
      public void pushClassTransformer(final List<String> entityClassNames) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isUseQuotedIdentifiers() {
        return property("hibernate.useQuotedIdentifiers", false);
      }

      @Override
      public boolean isExcludeUnlistedClasses() {
        return property("hibernate.excludeUnlistedClasses", false);
      }

      @Override
      public ValidationMode getValidationMode() {
        return ValidationMode.valueOf(property(AvailableSettings.VALIDATION_MODE, "NONE")
            .toUpperCase());
      }

      @Override
      public PersistenceUnitTransactionType getTransactionType() {
        return PersistenceUnitTransactionType.RESOURCE_LOCAL;
      }

      @Override
      public SharedCacheMode getSharedCacheMode() {
        return property(AvailableSettings.SHARED_CACHE_MODE, null);
      }

      @Override
      public String getProviderClassName() {
        return HibernatePersistenceProvider.class.getName();
      }

      @Override
      public Properties getProperties() {
        Properties $ = new Properties();
        config.getConfig("javax.persistence")
            .entrySet()
            .forEach(e -> $.put("javax.persistence" + e.getKey(), e.getValue().unwrapped()));
        return $;
      }

      @Override
      public URL getPersistenceUnitRootUrl() {
        if (scan) {
          ClassLoader loader = getClassLoader();
          return Optional.ofNullable(loader.getResource("")).orElse(loader.getResource("/"));
        } else {
          return null;
        }
      }

      @Override
      public Object getNonJtaDataSource() {
        return dataSourceHolder.get();
      }

      @Override
      public String getName() {
        return dataSourceHolder.toString();
      }

      @Override
      public List<String> getMappingFileNames() {
        return Collections.emptyList();
      }

      @Override
      public List<String> getManagedClassNames() {
        return Collections.emptyList();
      }

      @Override
      public Object getJtaDataSource() {
        return null;
      }

      @Override
      public List<URL> getJarFileUrls() {
        return null;
      }

      @Override
      public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
      }
    };
  }

  private static Map<Object, Object> config(final Config config, final List<Class<?>> classes) {
    Map<Object, Object> $ = new HashMap<>();
    config.getConfig("hibernate")
        .entrySet()
        .forEach(e -> $.put("hibernate." + e.getKey(), e.getValue().unwrapped()));

    if (classes.size() > 0) {
      $.put(AvailableSettings.LOADED_CLASSES, classes);
    }

    return $;
  }

}