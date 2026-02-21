// src/main/java/evebot/ClientWindowInfo.java
package evebot;

import java.awt.Rectangle;

public class ClientWindowInfo {
    private String title;
    private String windowClass;
    private long handle;  // Windows handle (HWND)
    private Rectangle bounds;
    private boolean isActive;
    private String clientId;

    public ClientWindowInfo() {
        this.bounds = new Rectangle();
    }

    public ClientWindowInfo(String title, String windowClass, long handle,
                            int x, int y, int width, int height) {
        this.title = title;
        this.windowClass = windowClass;
        this.handle = handle;
        this.bounds = new Rectangle(x, y, width, height);
        this.isActive = false;
        this.clientId = generateClientId(x, y);
    }

    private String generateClientId(int x, int y) {
        return String.format("eve_client_%d_%d", x, y);
    }

    // Getters and Setters
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getWindowClass() { return windowClass; }
    public void setWindowClass(String windowClass) { this.windowClass = windowClass; }

    public long getHandle() { return handle; }
    public void setHandle(long handle) { this.handle = handle; }

    public Rectangle getBounds() { return bounds; }
    public void setBounds(Rectangle bounds) { this.bounds = bounds; }

    public int getX() { return bounds.x; }
    public int getY() { return bounds.y; }
    public int getWidth() { return bounds.width; }
    public int getHeight() { return bounds.height; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public void updateBounds(int x, int y, int width, int height) {
        this.bounds.setBounds(x, y, width, height);
    }

    @Override
    public String toString() {
        return String.format("ClientWindowInfo{id='%s', title='%s', bounds=[%d,%d %dx%d]}",
                clientId, title, bounds.x, bounds.y, bounds.width, bounds.height);
    }
}