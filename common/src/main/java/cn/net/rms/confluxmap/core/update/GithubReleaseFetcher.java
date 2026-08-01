package cn.net.rms.confluxmap.core.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Live {@link UpdateCheckService.ReleaseFeedFetcher} over the GitHub REST API.
 * The list endpoint (not {@code /releases/latest}) is used deliberately:
 * {@code /latest} excludes prereleases, and this project's alpha builds are
 * published as prereleases.
 */
public final class GithubReleaseFetcher implements UpdateCheckService.ReleaseFeedFetcher {
    private static final String CONFLUX_MAP_RELEASES =
        "https://api.github.com/repos/Conflux-Union/conflux-map/releases?per_page=20";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final URI uri;
    private final String userAgent;

    public GithubReleaseFetcher(final URI uri, final String userAgent) {
        this.uri = uri;
        this.userAgent = userAgent;
    }

    public static GithubReleaseFetcher confluxMapReleases(final String modVersion) {
        return new GithubReleaseFetcher(URI.create(CONFLUX_MAP_RELEASES), "ConfluxMap/" + modVersion);
    }

    @Override
    public String fetchJson() throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .GET()
            .build();
        final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }
}
