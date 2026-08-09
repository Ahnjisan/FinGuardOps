package com.aifds.backend.common.time;

import java.time.Instant;

public interface DatabaseTransactionTimestampProvider {

    Instant currentTransactionTimestamp();
}
