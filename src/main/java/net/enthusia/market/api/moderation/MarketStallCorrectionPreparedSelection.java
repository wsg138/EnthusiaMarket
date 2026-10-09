package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Non-mutating, case-revision-bound exact stall selection and later revalidation.
 *
 * <p>Not a durable intent, staff authorization, mutation or commit receipt.
 * A mutating provider must repeat the full comparison within the same JDBC
 * transaction as the actual change and durable operation receipt.</p>
 */
public final class MarketStallCorrectionPreparedSelection {
    private MarketStallCorrectionPreparedSelection() {
    }

    public static Prepared prepare(Request request, List<MarketStallRecord> providerStalls) {
        Objects.requireNonNull(request, "request");
        MarketStallCorrectionPreflight.Selection selection = MarketStallCorrectionPreflight.select(
                request.operationId(), request.subjectId(), request.caseId(), request.stallId(),
                request.expectedWorld(), request.expectedRevision(), providerStalls);
        return new Prepared(request, selection.stall());
    }

    public record Request(
            UUID operationId,
            UUID reviewerId,
            UUID subjectId,
            String caseId,
            long caseRevision,
            String stallId,
            String expectedWorld,
            long expectedRevision,
            Instant preparedAt
    ) {
        public Request {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(reviewerId, "reviewerId");
            Objects.requireNonNull(subjectId, "subjectId");
            Objects.requireNonNull(preparedAt, "preparedAt");
            MarketApiValidation.identifier(caseId, "case id", 64);
            MarketApiValidation.identifier(stallId, "stall id", 128);
            MarketApiValidation.identifier(expectedWorld, "expected world", 128);
            if (caseRevision <= 0L || expectedRevision < 0L) {
                throw new IllegalArgumentException("Correction case and stall revisions are invalid");
            }
        }
    }

    public static final class Prepared {
        private final Request request;
        private final MarketStallRecord observed;

        private Prepared(Request request, MarketStallRecord observed) {
            this.request = request;
            this.observed = observed;
        }

        public Request request() {
            return request;
        }

        public MarketStallRecord observed() {
            return observed;
        }

        /** Does not confer authority or commit. */
        public MarketStallRecord revalidate(List<MarketStallRecord> providerStalls) {
            MarketStallCorrectionPreflight.Selection current = MarketStallCorrectionPreflight.select(
                    request.operationId(), request.subjectId(), request.caseId(), request.stallId(),
                    request.expectedWorld(), request.expectedRevision(), providerStalls);
            if (!observed.equals(current.stall())) {
                throw new IllegalStateException(
                        "Full market stall state changed since correction preparation");
            }
            return current.stall();
        }
    }
}
