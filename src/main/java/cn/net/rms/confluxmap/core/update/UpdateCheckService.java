package cn.net.rms.confluxmap.core.update;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.logging.log4j.Logger;

/**
 * One-shot background probe of the project's GitHub release feed. {@link #checkAsync}
 * runs at most once per instance on a short-lived daemon thread; the outcome lands in
 * {@link #available()} for pull-style consumers (chat notifier, fullscreen-map badge)
 * and in the optional callback for push-style ones (server console line). Failures are
 * routine - offline play, blocked API - so they cost one log line, never a stack trace.
 *
 * <p>Drafts are skipped but prereleases are compared like any other version: this
 * project ships alpha builds as GitHub prereleases, and hiding them would hide every
 * current release.
 */
public final class UpdateCheckService {
    /** Landing page fallback when a release entry carries no {@code html_url}. */
    public static final String RELEASES_PAGE = "https://github.com/Conflux-Union/conflux-map/releases";

    /** Transport seam so tests (and future non-GitHub sources) can inject the raw feed. */
    public interface ReleaseFeedFetcher {
        String fetchJson() throws IOException, InterruptedException;
    }

    public record UpdateInfo(String currentVersion, String latestVersion, String releaseUrl) {
    }

    private final String currentVersion;
    private final ReleaseFeedFetcher fetcher;
    private final Logger logger;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile UpdateInfo available;

    public UpdateCheckService(final String currentVersion, final ReleaseFeedFetcher fetcher, final Logger logger) {
        this.currentVersion = currentVersion;
        this.fetcher = fetcher;
        this.logger = logger;
    }

    /** The newer release a completed check found, or empty before/without one. */
    public Optional<UpdateInfo> available() {
        return Optional.ofNullable(available);
    }

    public void checkAsync() {
        checkAsync(null);
    }

    /** Fire-and-forget; only the first call per instance starts a check, later calls are no-ops. */
    public void checkAsync(final Consumer<UpdateInfo> onUpdateAvailable) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        final Thread thread = new Thread(() -> runCheck(onUpdateAvailable), "ConfluxMap-UpdateCheck");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocking body of {@link #checkAsync}; package-visible so tests drive it synchronously. */
    void runCheck(final Consumer<UpdateInfo> onUpdateAvailable) {
        try {
            final Optional<UpdateInfo> result = evaluate(currentVersion, GithubReleaseFeed.parse(fetcher.fetchJson()));
            if (result.isEmpty()) {
                logger.info("Update check: {} is up to date", currentVersion);
                return;
            }
            available = result.get();
            logger.info(
                "Update check: {} is available (installed {}): {}",
                result.get().latestVersion(), currentVersion, result.get().releaseUrl()
            );
            if (onUpdateAvailable != null) {
                onUpdateAvailable.accept(result.get());
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final IOException | RuntimeException e) {
            logger.info("Update check skipped: {}", e.toString());
        }
    }

    /** Pure decision step: the highest parseable non-draft release, if it beats the installed version. */
    static Optional<UpdateInfo> evaluate(final String currentVersion, final List<GithubReleaseFeed.Release> releases) {
        final Optional<ModVersion> current = ModVersion.parse(currentVersion);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        ModVersion bestVersion = null;
        GithubReleaseFeed.Release bestRelease = null;
        for (final GithubReleaseFeed.Release release : releases) {
            if (release.draft()) {
                continue;
            }
            final Optional<ModVersion> candidate = ModVersion.parse(release.tagName());
            if (candidate.isEmpty()) {
                continue;
            }
            if (bestVersion == null || candidate.get().compareTo(bestVersion) > 0) {
                bestVersion = candidate.get();
                bestRelease = release;
            }
        }
        if (bestVersion == null || bestVersion.compareTo(current.get()) <= 0) {
            return Optional.empty();
        }
        final String url = bestRelease.htmlUrl() != null ? bestRelease.htmlUrl() : RELEASES_PAGE;
        return Optional.of(new UpdateInfo(currentVersion, bestVersion.toString(), url));
    }
}
