package com.tieat.onboarding.application;

import java.util.List;

public interface StorePlaceSearchGateway {

    List<PlaceSearchResult> search(String query);

    record PlaceSearchResult(
        String placeId,
        String storeDisplayName,
        String address,
        String category
    ) {
    }
}
