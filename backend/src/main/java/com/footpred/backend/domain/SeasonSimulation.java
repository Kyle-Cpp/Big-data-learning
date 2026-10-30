package com.footpred.backend.domain;

/** One team's season-simulation odds (Job P5.2, partner package). */
public class SeasonSimulation {

    public String league;
    public String team;
    public double averagePoints;
    public double pointsStandardDeviation;
    public double averageRank;
    public double championshipProbability;
    public double topPlacesProbability;
    public double relegationProbability;
    public int actualRank;
    public int actualPoints;
    /** "1:0.641|2:0.247|..." ranking histogram. */
    public String rankHistogram;

    public SeasonSimulation() {
    }
}
