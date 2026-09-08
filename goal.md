# Android Remote Control

## Objective

Develop an Android companion application that allows a physical Android phone to be viewed and operated remotely through a web interface. The application should provide the capabilities required to expose the phone's live screen and receive remote interactions such as taps, swipes, scrolling, dragging, and keyboard input. The phone remains a normal Android device with its existing applications, phone number, SIM, contacts, notifications, and device state.

## CRM Integration

The Android application will be connected to our CRM web application, where the live Android screen will be displayed and interacted with. The CRM acts as the remote interface for the Android device.

---

# 1. Android Application

The application runs directly on the physical Android phone.

Its purpose is to provide the Android-side functionality required for:

* Live screen capture
* Remote interaction
* Device communication
* Session state
* User authorization

---

# 2. Live Screen

The application must provide a live representation of the Android display.

The screen should reflect the current state of the physical device, including:

* Android home screen
* Applications
* Phone application
* Messaging applications
* Contacts
* Notifications
* Other visible Android interfaces

The objective is a **live screen**, rather than periodic screenshots or static images.

**Relevant Android capability:**
[MediaProjection — Android API](https://developer.android.com/reference/kotlin/android/media/projection/MediaProjection?utm_source=chatgpt.com)

---

# 3. Remote Interaction

The Android application must support interaction with the device from the remote interface.

Target interactions include:

* Tap
* Swipe
* Scroll
* Drag
* Long press
* Keyboard input
* Navigation
* Multi-touch gestures where supported

The intended behavior is:

```text
Remote interaction
        ↓
Android device
        ↓
Android responds
        ↓
Updated screen
```

**Relevant Android capability:**
[AccessibilityService — Android API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService?utm_source=chatgpt.com)

Android provides `dispatchGesture()` for dispatching gestures to the touchscreen, including custom gestures such as taps, swipes, and multi-touch interactions.

---

# 4. Android Device

The physical phone remains the actual Android device.

The application should operate alongside the phone's normal functionality, including:

* SIM
* Phone number
* Cellular connection
* Installed applications
* Contacts
* Notifications
* Files
* Android system

The objective is to remotely operate the existing device rather than create a separate virtual phone.

---

# 5. User Authorization

The application must follow Android's permission and authorization model.

Required capabilities must be explicitly authorized by the user.

The user should have clear visibility into when:

* Screen sharing is active
* Remote interaction is active
* The Android application is operating

MediaProjection specifically provides Android applications with permission to capture screen contents, with the capabilities determined by the projection session.

---

# 6. Remote Session

The Android application should support a remote session with clear states:

```text
Unavailable
    ↓
Available
    ↓
Active
    ↓
Screen sharing
    ↓
Remote interaction
```

The application should handle:

* Starting a session
* Ending a session
* Temporary interruption
* Reconnection
* Session termination

---

# 7. Interaction Goal

The final experience should be equivalent to having the Android phone displayed as an interactive device:

```text
┌─────────────────────────┐
│      ANDROID PHONE      │
│                         │
│       ┌─────────┐       │
│       │         │       │
│       │  APP    │       │
│       │         │       │
│       │         │       │
│       └─────────┘       │
│                         │
│   ← Tap                 │
│   ← Swipe               │
│   ← Scroll              │
│   ← Type                │
│                         │
└─────────────────────────┘
```

The remote interface should reflect changes occurring on the physical Android device.

---

# 8. Reference Project

The following project demonstrates a similar concept of browser-based Android screen streaming and remote interaction:

[Android Web Control — GitHub](https://github.com/Nick-wq/Android-Web-Control?utm_source=chatgpt.com)

It demonstrates browser-based Android screen mirroring, WebRTC streaming, and remote mouse/keyboard input. The repository states that its source code is **not publicly available**, so it should be treated as a reference for the desired behavior rather than as an implementation dependency.

---

# 9. Relevant Android Documentation

### Screen capture

[MediaProjection API](https://developer.android.com/reference/kotlin/android/media/projection/MediaProjection?utm_source=chatgpt.com)

### Accessibility and interaction

[AccessibilityService API](https://developer.android.com/reference/kotlin/android/accessibilityservice/AccessibilityService?utm_source=chatgpt.com)

### Accessibility Service guide

[Create an Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service?utm_source=chatgpt.com)

### Gesture API

[GestureDescription API](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.html?utm_source=chatgpt.com)

---

# 10. Primary Goal

> **Build an Android application that exposes the phone's live screen and allows that screen to be remotely interacted with from a web interface.**

The Android application is the **phone-side component** responsible for making the physical Android device remotely viewable and interactable.
