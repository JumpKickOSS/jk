// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.http.ProxyUserInfo;
import cc.jumpkick.util.GitProxy;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Optional;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;
import org.eclipse.jgit.transport.http.JDKHttpConnection;
import org.jspecify.annotations.Nullable;

/**
 * JGit's http(s) connections, routed the way a {@code git} child is: through the {@code http.proxy}
 * a {@link GitProxy} answers for the URL, direct for an empty answer, and as JGit would on its own
 * when there is no answer or no {@link #call} is in progress on the thread.
 *
 * <p>JGit asks one process-wide {@link HttpConnectionFactory} for every connection and picks the
 * proxy from the JVM's default selector before it does, so the route is carried to the factory by
 * a {@link ScopedValue} bound around each backend operation, not by the transport.
 *
 * <p>A proxy URL carrying {@code user:password@} is answered when it challenges with a 407: the
 * credential goes to that connection's own {@link Authenticator}, which speaks only to that proxy,
 * never to an origin and never through the JVM-wide default.
 */
final class JGitProxyRoute implements HttpConnectionFactory2 {

    /** The JDK refuses Basic on an https CONNECT unless this lists no scheme ({@code ProxyEnvironment}). */
    private static final String TUNNELING_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    /** Answers the operation running on this thread routes by. */
    private static final ScopedValue<GitProxy> ROUTE = ScopedValue.newInstance();

    /** A JGit operation that may open connections. */
    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

    /** Where one connection goes: the proxy, and the credential the proxy's URL carried. */
    record Route(Proxy proxy, @Nullable PasswordAuthentication credential) {}

    private final HttpConnectionFactory delegate;

    private JGitProxyRoute(HttpConnectionFactory delegate) {
        this.delegate = delegate;
    }

    /** Run {@code op} with every JGit connection it opens routed by {@code proxy}. */
    static <T> T call(GitProxy proxy, Operation<T> op) throws IOException {
        install();
        return ScopedValue.where(ROUTE, proxy).call(op::run);
    }

    private static synchronized void install() {
        if (System.getProperty(TUNNELING_SCHEMES) == null) System.setProperty(TUNNELING_SCHEMES, "");
        HttpConnectionFactory current = HttpTransport.getConnectionFactory();
        if (!(current instanceof JGitProxyRoute)) HttpTransport.setConnectionFactory(new JGitProxyRoute(current));
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        Optional<Route> routed = route(url);
        return routed.isPresent() ? open(url, routed.get()) : delegate.create(url);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        Optional<Route> routed = route(url);
        return routed.isPresent() ? open(url, routed.get()) : delegate.create(url, proxy);
    }

    private HttpConnection open(URL url, Route route) throws IOException {
        PasswordAuthentication credential = route.credential();
        if (credential == null) return delegate.create(url, route.proxy());
        return new ProxyAuthenticatingConnection(url, route.proxy(), new ProxyCredential(route.proxy(), credential));
    }

    @Override
    public GitSession newSession() {
        if (delegate instanceof HttpConnectionFactory2 sessions) return sessions.newSession();
        return new GitSession() {
            @Override
            public HttpConnection configure(HttpConnection connection, boolean sslVerify) {
                return connection;
            }

            @Override
            public void close() {}
        };
    }

    /** The route {@code url} takes, {@link Proxy#NO_PROXY} for direct, or empty for JGit's own choice. */
    static Optional<Route> route(URL url) {
        if (!ROUTE.isBound()) return Optional.empty();
        return ROUTE.get().httpProxy(url.toString()).map(JGitProxyRoute::parse);
    }

    /** {@code value}, a {@code http.proxy} setting, as a route; the empty value is git's "no proxy". */
    static Route parse(String value) {
        if (value.isEmpty()) return new Route(Proxy.NO_PROXY, null);
        URI uri = URI.create(value.contains("://") ? value : "http://" + value);
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("a proxy URL names no host: " + uri.getScheme() + "://…");
        // jk's proxies are http proxies, on port 80 unless the URL says otherwise (ProxyEnvironment).
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        String userInfo = uri.getRawUserInfo();
        return new Route(proxy, userInfo == null ? null : ProxyUserInfo.credential(userInfo));
    }

    /** Answers the one proxy it was made for, and nobody else. */
    static final class ProxyCredential extends Authenticator {
        private final InetSocketAddress proxy;
        private final PasswordAuthentication credential;

        ProxyCredential(Proxy proxy, PasswordAuthentication credential) {
            this.proxy = (InetSocketAddress) proxy.address();
            this.credential = credential;
        }

        @Override
        protected @Nullable PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) return null;
            String host = getRequestingHost();
            if (host == null || !host.equalsIgnoreCase(proxy.getHostString())) return null;
            if (getRequestingPort() != proxy.getPort()) return null;
            return credential;
        }
    }

    /**
     * A JGit connection whose {@link HttpURLConnection} carries {@code authenticator}. JGit opens the
     * JDK connection inside its own constructor, so the URL it is handed opens through a handler
     * that sets the authenticator on the connection it returns.
     */
    static final class ProxyAuthenticatingConnection extends JDKHttpConnection {
        ProxyAuthenticatingConnection(URL url, Proxy proxy, Authenticator authenticator) throws IOException {
            super(withAuthenticator(url, authenticator), proxy);
        }

        private static URL withAuthenticator(URL url, Authenticator authenticator) throws IOException {
            URLStreamHandler handler = new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL u) throws IOException {
                    return openConnection(u, Proxy.NO_PROXY);
                }

                @Override
                protected URLConnection openConnection(URL u, Proxy p) throws IOException {
                    URLConnection opened = url.openConnection(p);
                    if (opened instanceof HttpURLConnection http) http.setAuthenticator(authenticator);
                    return opened;
                }
            };
            try {
                return URL.of(url.toURI(), handler);
            } catch (URISyntaxException e) {
                throw new IOException("not a URI: " + url.getProtocol() + "://" + url.getHost(), e);
            }
        }
    }
}
