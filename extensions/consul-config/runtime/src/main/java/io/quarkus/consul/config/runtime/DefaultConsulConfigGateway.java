package io.quarkus.consul.config.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Optional;

import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustAllStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

class DefaultConsulConfigGateway implements ConsulConfigGateway {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ConsulConfig consulConfig;
    private final SSLConnectionSocketFactory sslSocketFactory;

    public DefaultConsulConfigGateway(ConsulConfig consulConfig) {
        this.consulConfig = consulConfig;
        if (consulConfig.agent.keyStore.isPresent()) {
            this.sslSocketFactory = createFactoryFromKeyStore(consulConfig.agent.keyStore.get(),
                    consulConfig.agent.keyStorePassword);
        } else if (consulConfig.agent.trustCerts) {
            this.sslSocketFactory = createAllTrustingFactory();
        } else {
            this.sslSocketFactory = null;
        }

    }

    private SSLConnectionSocketFactory createFactoryFromKeyStore(Path keyStorePath, Optional<String> keyStorePassword) {
        try {
            return new SSLConnectionSocketFactory(
                    SSLContexts.custom()
                            // make sure we only trust the certificates in the keystore and nothing else
                            .loadTrustMaterial(readStore(keyStorePath, keyStorePassword), null)
                            .build(),
                    NoopHostnameVerifier.INSTANCE);
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException | IOException | CertificateException e) {
            throw new RuntimeException(e);
        }
    }

    private static String findKeystoreFileType(Path keyStorePath) {
        String pathName = keyStorePath.toString().toLowerCase();
        if (pathName.endsWith(".p12") || pathName.endsWith(".pkcs12") || pathName.endsWith(".pfx")) {
            return "PKS12";
        }
        return "JKS";
    }

    private static KeyStore readStore(Path keyStorePath, Optional<String> keyStorePassword)
            throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException {

        String keyStoreType = findKeystoreFileType(keyStorePath);

        InputStream classPathResource = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(keyStorePath.toString());
        if (classPathResource != null) {
            try (InputStream is = classPathResource) {
                return doReadStore(is, keyStoreType, keyStorePassword);
            }
        } else {
            try (InputStream is = Files.newInputStream(keyStorePath)) {
                return doReadStore(is, keyStoreType, keyStorePassword);
            }
        }
    }

    private static KeyStore doReadStore(InputStream keyStoreStream, String keyStoreType, Optional<String> keyStorePassword)
            throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException {
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        keyStore.load(keyStoreStream, keyStorePassword.isPresent() ? keyStorePassword.get().toCharArray() : null);
        return keyStore;
    }

    private SSLConnectionSocketFactory createAllTrustingFactory() {
        try {
            return new SSLConnectionSocketFactory(
                    SSLContexts.custom().loadTrustMaterial(TrustAllStrategy.INSTANCE).build(),
                    NoopHostnameVerifier.INSTANCE);
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<Response> getValue(String key) throws IOException {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout((int) consulConfig.agent.connectionTimeout.toMillis())
                .setSocketTimeout((int) consulConfig.agent.readTimeout.toMillis())
                .build();
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setDefaultRequestConfig(requestConfig);
        if (sslSocketFactory != null) {
            httpClientBuilder.setSSLSocketFactory(sslSocketFactory);
        }
        try (CloseableHttpClient client = httpClientBuilder.build()) {
            String finalUri = (consulConfig.agent.useHttps ? "https" : "http") + "://"
                    + consulConfig.agent.hostPort.getHostName() + ":" + consulConfig.agent.hostPort.getPort()
                    + "/v1/kv/"
                    + key;
            HttpGet request = new HttpGet(finalUri);
            request.addHeader("Accept", "application/json");
            if (consulConfig.agent.token.isPresent()) {
                request.addHeader("Authorization", "Bearer " + consulConfig.agent.token);
            }

            try (CloseableHttpResponse response = client.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == HttpStatus.SC_NOT_FOUND) {
                    return Optional.empty();
                }
                if (statusCode != HttpStatus.SC_OK) {
                    throw new RuntimeException("Got unexpected HTTP response code " + statusCode
                            + " from " + finalUri);
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) {
                    throw new RuntimeException("Got empty HTTP response body " + finalUri);
                }

                List<Response> value = OBJECT_MAPPER.readValue(EntityUtils.toString(entity),
                        new TypeReference<List<Response>>() {
                        });
                if (value.size() != 1) {
                    throw new IllegalStateException(
                            "Consul returned an unexpected number of results when looking up value of key '" + key + "'");
                }
                return Optional.of(value.get(0));
            }
        }
    }
}
