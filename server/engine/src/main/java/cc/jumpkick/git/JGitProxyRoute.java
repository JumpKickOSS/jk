// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.util.GitProxy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.util.Optional;
import org.eclipse.jgit.transport.HttpTransport;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.transport.http.HttpConnectionFactory2;

/**
 * JGit's http(s) connections, routed the way a {@code git} child is: through the {@code http.proxy}
 * a {@link GitProxy} answers for the URL, direct for an empty answer, and as JGit would on its own
 * when there is no answer or no {@link #call} is in progress on the thread.
 *
 * <p>JGit asks one process-wide {@link HttpConnectionFactory} for every connection and picks the
 * proxy from the JVM's default selector before it does, so the route is carried to the factory by
 * a {@link ScopedValue} bound around each backend operation, not by the transport.
 */
final class JGitProxyRoute implements HttpConnectionFactory2 {

    /** Answers the operation running on this thread routes by. */
    private static final ScopedValue<GitProxy> ROUTE = ScopedValue.newInstance();

    /** A JGit operation that may open connections. */
    @FunctionalInterface
    interface Operation<T> {
        T run() throws IOException;
    }

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
        HttpConnectionFactory current = HttpTransport.getConnectionFactory();
        if (!(current instanceof JGitProxyRoute)) HttpTransport.setConnectionFactory(new JGitProxyRoute(current));
    }

    @Override
    public HttpConnection create(URL url) throws IOException {
        Optional<Proxy> routed = route(url);
        return routed.isPresent() ? delegate.create(url, routed.get()) : delegate.create(url);
    }

    @Override
    public HttpConnection create(URL url, Proxy proxy) throws IOException {
        return delegate.create(url, route(url).orElse(proxy));
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

    /** The proxy {@code url} goes through, {@link Proxy#NO_PROXY} for direct, or empty for JGit's own choice. */
    static Optional<Proxy> route(URL url) {
        if (!ROUTE.isBound()) return Optional.empty();
        return ROUTE.get().httpProxy(url.toString()).map(JGitProxyRoute::proxy);
    }

    /** {@code value}, a {@code http.proxy} setting, as a JDK proxy; the empty value is git's "no proxy". */
    static Proxy proxy(String value) {
        if (value.isEmpty()) return Proxy.NO_PROXY;
        URI uri = URI.create(value.contains("://") ? value : "http://" + value);
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("a proxy URL names no host: " + uri.getScheme() + "://…");
        // jk's proxies are http proxies, on port 80 unless the URL says otherwise (ProxyEnvironment).
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }
}
