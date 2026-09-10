package com.autostock.risk;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** DisclosureBlacklistExpiryScheduler — releaseExpiredNow()가 DisclosureBlacklist.releaseExpired()로 위임하는지만 확인한다. */
class DisclosureBlacklistExpirySchedulerTest {

    @Test
    void releaseExpiredNow는_DisclosureBlacklist_releaseExpired를_호출한다() {
        DisclosureBlacklist blacklist = mock(DisclosureBlacklist.class);
        DisclosureBlacklistExpiryScheduler scheduler = new DisclosureBlacklistExpiryScheduler(blacklist);

        scheduler.releaseExpiredNow();

        verify(blacklist, times(1)).releaseExpired();
    }
}
