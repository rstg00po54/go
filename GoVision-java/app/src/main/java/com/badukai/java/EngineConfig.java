package com.badukai.java;

public class EngineConfig {
    public String executablePath = "";
    public String modelPath = "";
    public String configPath = "";
    public int threads = 2;
    public int visits = 800;
    public float komi = 7.5f;
    public boolean isReady() { return !executablePath.isEmpty() && !modelPath.isEmpty(); }
}
