package evebot.models;

import java.time.LocalDateTime;

public class ClientState {
    public enum State {
        IDLE, CHECKING_MISSIONS, ACCEPTED, IN_COMBAT, RETURNING, COMPLETING
    }

    private String clientId;
    private State currentState;
    private Mission currentMission;
    private LocalDateTime lastStateChange;
    private LocalDateTime lastMissionCheck;

    // Constructors, getters, and setters
    public ClientState(String clientId) {
        this.clientId = clientId;
        this.currentState = State.IDLE;
        this.lastStateChange = LocalDateTime.now();
    }

    // Getters and setters
    public String getClientId() { return clientId; }
    public State getState() { return currentState; }
    public void setState(State state) {
        this.currentState = state;
        this.lastStateChange = LocalDateTime.now();
    }
    public Mission getCurrentMission() { return currentMission; }
    public void setCurrentMission(Mission mission) { this.currentMission = mission; }
}