# Final System Architecture & QA Audit
**Lead Analyst:** Harjot Singh Siddhu
**Date:** November 2026

## 1. Architectural Evaluation
The current sensor fusion backend relies heavily on raw device sensor data. While sufficient for short, controlled distances, the architecture lacks aggressive noise filtering (like an Extended Kalman Filter), leading to notable sensor drift when the device is stationary or obstructed.

## 2. Vulnerability & Edge Case Testing
* **Stationary Drift Failure:** During a 2-minute idle test, the localization engine incorrectly registered physical movement due to raw accelerometer noise not being properly filtered out.
* **Orientation Loss:** The Pedestrian Dead Reckoning (PDR) logic fails to maintain heading accuracy when the device is placed in a pocket, as the local coordinate frame desynchronizes from the global map without visual SLAM to correct it.
* **Vertical Routing Ambiguity:** The Z-axis floor detection struggles with rapid altitude changes, failing to smoothly transition the 3D UI states during stair climbing.

## 3. Proposed Optimization Roadmap
To resolve these critical pathfinding errors in V2, the localization architecture must integrate an Extended Kalman Filter (EKF) and utilize Monte Carlo Particle Filtering to lock the user's position to known walkable paths on the 3D map grid.