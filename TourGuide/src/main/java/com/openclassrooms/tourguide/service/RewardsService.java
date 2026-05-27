package com.openclassrooms.tourguide.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;

import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {

	private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;

	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<Attraction> attractions = gpsUtil.getAttractions();

		Set<String> rewardedAttractionNames = user.getUserRewards()
				.stream()
				.map(userReward -> userReward.attraction.attractionName)
				.collect(Collectors.toSet());

		for (Attraction attraction : attractions) {
			if (!rewardedAttractionNames.contains(attraction.attractionName)) {
				Optional<VisitedLocation> matchingVisitedLocation = userLocations
						.stream()
						.filter(visitedLocation -> nearAttraction(visitedLocation, attraction))
						.findFirst();

				if (matchingVisitedLocation.isPresent()) {
					user.addUserReward(new UserReward(
							matchingVisitedLocation.get(),
							attraction,
							getRewardPoints(attraction.attractionId, user.getUserId())
					));

					rewardedAttractionNames.add(attraction.attractionName);
				}
			}
		}
	}

	public void calculateRewardsForAllUsers(List<User> users) {
		int threadPoolSize = Math.min(
				100,
				Math.max(10, Runtime.getRuntime().availableProcessors() * 4)
		);

		ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);

		try {
			List<CompletableFuture<Void>> futures = users.stream()
					.map(user -> CompletableFuture.runAsync(
							() -> calculateRewards(user),
							executorService
					))
					.collect(Collectors.toList());

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
		} finally {
			executorService.shutdown();
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	public int getRewardPoints(UUID attractionId, UUID userId) {
		return rewardsCentral.getAttractionRewardPoints(attractionId, userId);
	}

	public double getDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math.acos(
				Math.sin(lat1) * Math.sin(lat2)
						+ Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2)
		);

		double nauticalMiles = 60 * Math.toDegrees(angle);

		return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
	}
}