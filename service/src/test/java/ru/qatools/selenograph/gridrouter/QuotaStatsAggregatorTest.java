package ru.qatools.selenograph.gridrouter;

import org.apache.camel.component.mock.MockEndpoint;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import ru.qatools.selenograph.plugins.HubBrowserStateAggregator;
import ru.yandex.qatools.camelot.plugin.GraphiteReportProcessor;
import ru.yandex.qatools.camelot.plugin.GraphiteValue;
import ru.yandex.qatools.camelot.test.*;

import java.util.Set;
import java.util.UUID;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsCollectionContaining.hasItem;
import static org.mockito.Mockito.*;
import static ru.yandex.qatools.matchers.decorators.MatcherDecorators.should;
import static ru.yandex.qatools.matchers.decorators.TimeoutWaiter.timeoutHasExpired;

/**
 * @author Ilya Sadykov
 */
@RunWith(CamelotTestRunner.class)
@DisableTimers
public class QuotaStatsAggregatorTest {
    public static final int TIMEOUT = 3000;

    @Helper
    TestHelper helper;

    @Autowired
    SessionsAggregator sessions;

    @AggregatorState(QuotaStatsAggregator.class)
    AggregatorStateStorage statsStorage;

    @AggregatorState(SessionsAggregator.class)
    AggregatorStateStorage sessionsStorage;

    @PluginMock
    QuotaStatsAggregator quotaStatsMock;

    @PluginMock
    GraphiteReportProcessor graphite;

    @PluginMock
    SessionsAggregator sessionsMock;

    @EndpointPluginInput(HubBrowserStateAggregator.class)
    MockEndpoint nodeBrowserState;

    @Test
    public void testStartMultipleSessions() throws Exception {
        // Launch 3 sessions
        expectNodeBrowserStateReceived(3);
        String sessionId1 = startSessionFor("vasya");
        String sessionId2 = startSessionFor("vasya");
        String sessionId3 = startSessionFor("vasya");
        verifyNodeBrowserStateReceived();
        assertSessionStateFor("vasya", sessionId1, notNullValue());
        assertSessionStateFor("vasya", sessionId2, notNullValue());
        assertSessionStateFor("vasya", sessionId3, notNullValue());

        await().atMost(4, SECONDS).until(() -> activeSessionsFor("vasya"),
                hasItems(sessionId1, sessionId2, sessionId3));
        assertThat(sessions.getSessionsCountForUser("vasya"), is(3));

        helper.invokeTimersFor(SessionsAggregator.class);
        verifyQuotaStatsReceived(1);
        assertStatsStateFor("vasya", notNullValue());
        SessionsState vasyaStats = await().atMost(2, SECONDS).until(() -> statsFor("vasya"), notNullValue());
        assertThat(vasyaStats.getAvg(), is(2));
        assertThat(vasyaStats.getMax(), is(3));
        assertThat(vasyaStats.getRaw(), is(3));

        // Stop two sessions
        expectNodeBrowserStateReceived(2);
        stopSessionFor("vasya", sessionId1);
        stopSessionFor("vasya", sessionId2);
        verifyNodeBrowserStateReceived();
        await().atMost(2, SECONDS).until(() -> activeSessionsFor("vasya"),
                allOf(not(hasItem(sessionId1)), not(hasItem(sessionId2))));
        assertThat(sessions.getSessionsCountForUser("vasya"), is(1));

        helper.invokeTimersFor(SessionsAggregator.class);
        verifyQuotaStatsReceived(1);
        await().atMost(2, SECONDS).until(() -> statsFor("vasya").getRaw(), is(1));
        assertThat(statsFor("vasya").getAvg(), is(2));
        assertThat(statsFor("vasya").getMax(), is(3));
        assertThat(statsFor("vasya").getRaw(), is(1));

        // Start one more session
        expectNodeBrowserStateReceived(1);
        String sessionId4 = startSessionFor("vasya");
        verifyNodeBrowserStateReceived();
        assertSessionStateFor("vasya", sessionId4, notNullValue());
        await().atMost(2, SECONDS).until(() -> activeSessionsFor("vasya"), hasItem(sessionId4));
        assertThat(sessions.getSessionsCountForUser("vasya"), is(2));
        assertThat(sessions.getActiveSessions(), containsInAnyOrder(sessionId3, sessionId4));

        helper.invokeTimersFor(SessionsAggregator.class);
        verifyQuotaStatsReceived(1);
        await().atMost(2, SECONDS).until(() -> statsFor("vasya").getRaw(), is(2));
        assertThat(statsFor("vasya").getAvg(), is(2));
        assertThat(statsFor("vasya").getMax(), is(3));
        assertThat(statsFor("vasya").getRaw(), is(2));

        // Start one more session
        expectNodeBrowserStateReceived(1);
        String sessionId5 = startSessionFor("petya");
        verifyNodeBrowserStateReceived();
        assertSessionStateFor("petya", sessionId5, notNullValue());
        await().atMost(2, SECONDS).until(() -> activeSessionsFor("petya"), hasItem(sessionId5));
        assertThat(sessions.getSessionsCountForUser("petya"), is(1));
        assertThat(sessions.getSessionsCountForUser("vasya"), is(2));
        assertThat(sessions.getActiveSessions(), containsInAnyOrder(sessionId3, sessionId4, sessionId5));
        helper.invokeTimersFor(SessionsAggregator.class);
        verifyQuotaStatsReceived(2);
        await().atMost(2, SECONDS).until(() -> statsFor("petya"), notNullValue());
        assertThat(statsFor("petya").getAvg(), is(1));
        assertThat(statsFor("petya").getMax(), is(1));
        assertThat(statsFor("petya").getRaw(), is(1));

        helper.invokeTimersFor(QuotaStatsAggregator.class);
        verify(graphite, timeout(TIMEOUT).times(6)).process(Mockito.any(GraphiteValue.class));
    }

    private SessionsState statsFor(String user) {
        return sessions.getStats(user).get(keyFor(user));
    }

    private void verifyQuotaStatsReceived(int times) {
        verify(quotaStatsMock, timeout(4000).times(times))
                .beforeCreate(Mockito.any(), Mockito.any(), Mockito.any());
        reset(quotaStatsMock);
    }

    private void verifyNodeBrowserStateReceived() throws InterruptedException {
        nodeBrowserState.assertIsSatisfied();
    }

    private void expectNodeBrowserStateReceived(int count) {
        nodeBrowserState.reset();
        nodeBrowserState.expectedMessageCount(count);
    }

    private void assertStatsStateFor(String user, Matcher<Object> matcher) {
        assertThat(sessionsStorage.get(SessionEvent.class, keyFor(user)),
                should(matcher).whileWaitingUntil(timeoutHasExpired(TIMEOUT)));
    }

    private void assertSessionStateFor(String user, String sessionId, Matcher<Object> matcher) {
        assertThat(sessionsStorage.get(SessionEvent.class, keyFor(user) + ":" + sessionId),
                should(matcher).whileWaitingUntil(timeoutHasExpired(TIMEOUT)));
    }

    private Set<String> activeSessionsFor(String user) {
        return sessions.sessionsByUser(user).stream().map(SessionEvent::getSessionId).collect(toSet());
    }

    private String keyFor(String user) {
        return user + ":firefox:33.0";
    }

    private void stopSessionFor(String user, String sessionId) {
        sessions.deleteSession(sessionId);
    }

    private String startSessionFor(String user) {
        final String sessionId = UUID.randomUUID().toString();
        sessions.startSession(sessionId, user, "firefox", "33.0");
        return sessionId;
    }
}
