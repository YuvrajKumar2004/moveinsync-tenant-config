package com.moveinsync.config.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.change.ChangeEvent;
import com.moveinsync.config.core.change.ChangeKind;
import com.moveinsync.config.core.change.DiffEntry;
import com.moveinsync.config.core.change.Op;
import com.moveinsync.config.core.wire.Wire;
import com.moveinsync.config.core.wire.WireCodec;
import com.moveinsync.config.server.repository.ChangeSetRecord;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.List;

/** JSON (de)serialisation of change log payloads. */
@Component
public class EventJson {

    private static final TypeReference<List<Wire.Op>> OPS = new TypeReference<>() {
    };
    private static final TypeReference<List<DiffEntry>> DIFF = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public EventJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String ops(List<Op> ops) {
        return write(WireCodec.toWire(ops));
    }

    public List<Op> ops(String json) {
        return WireCodec.fromWire(read(json, OPS));
    }

    public List<Wire.Op> wireOps(String json) {
        return read(json, OPS);
    }

    public String diff(List<DiffEntry> diff) {
        return write(diff);
    }

    public List<DiffEntry> diff(String json) {
        return read(json, DIFF);
    }

    public ChangeEvent event(ChangeSetRecord r) {
        return new ChangeEvent(r.version(), r.committedAt().toEpochMilli(), ChangeKind.valueOf(r.kind()), ops(r.opsJson()));
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
