package cn.net.rms.confluxmap.core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

/** Feed parsing, update decision, and async lifecycle of {@link UpdateCheckService}. */
class UpdateCheckServiceTest {

    private static final Logger LOGGER = LogManager.getLogger("UpdateCheckServiceTest");

    private static final String FEED = "["
        + "{\"tag_name\":\"v0.1.0-alpha.2\",\"html_url\":\"https://example.test/a2\",\"draft\":false,\"prerelease\":true},"
        + "{\"tag_name\":\"v0.2.0\",\"html_url\":\"https://example.test/v020\",\"draft\":false,\"prerelease\":false},"
        + "{\"tag_name\":\"v9.9.9\",\"html_url\":\"https://example.test/draft\",\"draft\":true,\"prerelease\":false},"
        + "{\"tag_name\":\"nightly-build\",\"html_url\":\"https://example.test/junk\",\"draft\":false,\"prerelease\":true},"
        + "{\"html_url\":\"https://example.test/tagless\"},"
        + "\"not-an-object\""
        + "]";

    @Test
    void parseExtractsReleasesAndDropsTaglessEntries() {
        final List<GithubReleaseFeed.Release> releases = GithubReleaseFeed.parse(FEED);
        assertEquals(4, releases.size());
        assertEquals("v0.1.0-alpha.2", releases.get(0).tagName());
        assertEquals("https://example.test/a2", releases.get(0).htmlUrl());
        assertTrue(releases.get(0).prerelease());
        assertTrue(releases.get(2).draft());
    }

    @Test
    void evaluatePicksHighestNonDraftRelease() {
        final Optional<UpdateCheckService.UpdateInfo> info =
            UpdateCheckService.evaluate("0.1.0-alpha.4", GithubReleaseFeed.parse(FEED));
        assertTrue(info.isPresent());
        // The draft v9.9.9 and the unparseable nightly tag are skipped; v0.2.0 wins.
        assertEquals("0.2.0", info.get().latestVersion());
        assertEquals("https://example.test/v020", info.get().releaseUrl());
        assertEquals("0.1.0-alpha.4", info.get().currentVersion());
    }

    @Test
    void evaluateIsEmptyWhenUpToDateOrAhead() {
        final List<GithubReleaseFeed.Release> releases = GithubReleaseFeed.parse(FEED);
        assertTrue(UpdateCheckService.evaluate("0.2.0", releases).isEmpty());
        assertTrue(UpdateCheckService.evaluate("0.3.0-alpha.1", releases).isEmpty());
        assertTrue(UpdateCheckService.evaluate("not-a-version", releases).isEmpty());
        assertTrue(UpdateCheckService.evaluate("0.1.0", List.of()).isEmpty());
    }

    @Test
    void evaluateFallsBackToReleasesPageWithoutHtmlUrl() {
        final List<GithubReleaseFeed.Release> releases = List.of(
            new GithubReleaseFeed.Release("v0.5.0", null, false, false)
        );
        final Optional<UpdateCheckService.UpdateInfo> info = UpdateCheckService.evaluate("0.1.0", releases);
        assertTrue(info.isPresent());
        assertEquals(UpdateCheckService.RELEASES_PAGE, info.get().releaseUrl());
    }

    @Test
    void runCheckStoresResultAndNotifies() {
        final UpdateCheckService service = new UpdateCheckService("0.1.0-alpha.4", () -> FEED, LOGGER);
        final AtomicReference<UpdateCheckService.UpdateInfo> notified = new AtomicReference<>();

        service.runCheck(notified::set);

        assertEquals("0.2.0", notified.get().latestVersion());
        assertEquals("0.2.0", service.available().orElseThrow().latestVersion());
    }

    @Test
    void runCheckSurvivesFetcherFailureWithoutResult() {
        final UpdateCheckService service = new UpdateCheckService(
            "0.1.0-alpha.4",
            () -> {
                throw new IOException("offline");
            },
            LOGGER
        );
        service.runCheck(info -> {
            throw new AssertionError("no update must be reported on fetch failure");
        });
        assertTrue(service.available().isEmpty());
    }

    @Test
    void runCheckSurvivesMalformedFeed() {
        final UpdateCheckService service = new UpdateCheckService("0.1.0", () -> "{\"oops\":true}", LOGGER);
        service.runCheck(null);
        assertTrue(service.available().isEmpty());
    }

    @Test
    void checkAsyncRunsTheFetchOnlyOnce() throws InterruptedException {
        final AtomicInteger fetches = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        final UpdateCheckService service = new UpdateCheckService(
            "0.1.0-alpha.4",
            () -> {
                fetches.incrementAndGet();
                return FEED;
            },
            LOGGER
        );

        service.checkAsync(info -> done.countDown());
        service.checkAsync(info -> done.countDown());

        assertTrue(done.await(10, TimeUnit.SECONDS), "check thread should complete");
        assertEquals(1, fetches.get());
        assertEquals("0.2.0", service.available().orElseThrow().latestVersion());
    }
}
