package com.minipay.network.internal.tracker;

import com.minipay.network.api.TrackedMessage;
import com.minipay.network.api.TrackerApi;
import com.minipay.network.api.TrackerSummary;
import com.minipay.network.api.TrackerView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TrackerService implements TrackerApi {

    private final TrackerRepository repo;

    public TrackerService(TrackerRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<TrackerView> track(String uetr) {
        UUID id;
        try {
            id = UUID.fromString(uetr);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<TrackedMessage> messages = repo.forUetr(id);
        return messages.isEmpty() ? Optional.empty() : Optional.of(RouteBuilder.build(id.toString(), messages));
    }

    @Override
    public List<TrackerSummary> recent(int limit) {
        return repo.recentUetrs(Math.min(Math.max(limit, 1), 200)).stream().map(u -> {
            List<TrackedMessage> messages = repo.forUetr(u);
            TrackerView v = RouteBuilder.build(u.toString(), messages);
            return new TrackerSummary(u.toString(), v.status(), v.debtorAgentBic(), v.creditorAgentBic(), v.sentAmount(),
                    messages.size(), messages.get(messages.size() - 1).sentAt());
        }).toList();
    }
}
