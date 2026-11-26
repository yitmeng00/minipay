package com.minipay.network.api;

import java.util.List;
import java.util.Optional;

/** The UETR tracker: the network's view of a payment, across every bank on its route. */
public interface TrackerApi {

    Optional<TrackerView> track(String uetr);

    /** Most recently active payments first. */
    List<TrackerSummary> recent(int limit);
}
