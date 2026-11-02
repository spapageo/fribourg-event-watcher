package ch.fribourgevents.store;

public record UpsertResult(
    EventEntity event, boolean eventCreated, boolean sourceRefCreated, boolean payloadChanged) {}
