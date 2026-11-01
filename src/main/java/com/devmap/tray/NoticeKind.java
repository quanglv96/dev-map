package com.devmap.tray;

/** The events that may pop up (FR-64); each can be turned off on its own (FR-66). Everything else only updates the icon. */
public enum NoticeKind {
  DECISION, QUESTION, PUSH, FAILURE, SYNC
}
