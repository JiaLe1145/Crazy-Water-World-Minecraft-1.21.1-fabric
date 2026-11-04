package dev.nbw.round;

import dev.nbw.NBW;
import dev.nbw.domain.round.Phase;
import dev.nbw.domain.round.RoundEvent;
import dev.nbw.domain.round.RoundMachine;
import dev.nbw.domain.round.RoundSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 回合状态落盘（03 §6.1"每次转移落盘"、Gate G1"重启能恢复到当前阶段"）。
 *
 * <p>三条硬要求：
 * <ol>
 *   <li><b>原子写</b>：临时文件 + {@code ATOMIC_MOVE}。长期服随时可能被 kill，
 *       半截 JSON 比没有 JSON 糟糕得多——那会让重启直接走进"状态未知"分支。</li>
 *   <li><b>读不出不等于读坏了</b>：文件不存在是正常的首次启动；存在但解析失败必须记 ERROR
 *       并让调用方明确选择"从 LOBBY 干净启动"，绝不静默按默认值跑。</li>
 *   <li><b>写入型数据与只读配置严格分离</b>（03 §5）：本文件只写 {@code <world>/cww/state/}，
 *       绝不碰 {@code arenas/} 与 {@code formats/}。</li>
 * </ol>
 */
public final class RoundStateStore {

	public static final int SCHEMA_VERSION = 1;
	public static final String FILE_NAME = "round.json";
	/** 落盘保留的审计尾部条数。全量落盘会让文件随运行时长无限膨胀。 */
	public static final int AUDIT_KEEP = 60;

	private static final Gson GSON = new Gson();

	/** 读取结果。{@code ABSENT} 与 {@code INVALID} 的处理方式完全不同，不能合并成一个 Optional。 */
	public enum Status { ABSENT, OK, INVALID }

	public record Load(Status status, RoundSnapshot snapshot, List<String> errors, Path file) {

		public boolean ok() {
			return status == Status.OK;
		}

		public static Load absent(Path file) {
			return new Load(Status.ABSENT, null, List.of(), file);
		}

		public static Load invalid(Path file, List<String> errors) {
			return new Load(Status.INVALID, null, errors, file);
		}
	}

	private RoundStateStore() {
	}

	public static Path stateFile(Path worldRoot) {
		return worldRoot.resolve("cww").resolve("state").resolve(FILE_NAME);
	}

	// ---------------------------------------------------------------- 写

	public static void write(Path worldRoot, RoundSnapshot snap) {
		Path target = stateFile(worldRoot);
		try {
			Files.createDirectories(target.getParent());
			String json = GSON.toJson(toJson(snap));
			Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			try {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				// Windows 上跨卷或某些网络盘会走到这里；退化成普通移动仍然比直接写目标安全
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			// 落盘失败不能拖崩对局（04 §6 降级要求），但必须吵：静默失败等于承诺了没做到的恢复能力
			NBW.LOGGER.error("[state] 回合状态写入失败（对局继续，但本次重启将无法恢复）：{}", target, e);
		}
	}

	// ---------------------------------------------------------------- 读

	public static Load read(Path worldRoot) {
		Path file = stateFile(worldRoot);
		if (!Files.isRegularFile(file)) {
			return Load.absent(file);
		}
		List<String> errors = new ArrayList<>();
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			RoundSnapshot snap = fromJson(root.getAsJsonObject(), errors);
			if (!errors.isEmpty()) {
				return Load.invalid(file, errors);
			}
			return new Load(Status.OK, snap.validate(), null, file);
		} catch (IOException | IllegalStateException | UnsupportedOperationException
				| RoundSnapshot.InvalidSnapshot | com.google.gson.JsonParseException e) {
			errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
			return Load.invalid(file, errors);
		}
	}

	// ---------------------------------------------------------------- JSON 翻译

	private static JsonObject toJson(RoundSnapshot snap) {
		JsonObject o = new JsonObject();
		o.addProperty("schemaVersion", SCHEMA_VERSION);
		o.addProperty("writtenAt", Instant.now().toString());
		o.addProperty("phase", snap.phase().name());
		o.addProperty("tickInPhase", snap.tickInPhase());
		o.addProperty("totalTicks", snap.totalTicks());
		o.addProperty("matchIndex", snap.matchIndex());
		o.addProperty("matchStartTick", snap.matchStartTick());
		o.addProperty("autoStart", snap.autoStart());
		o.addProperty("winnerTeamIndex", snap.winnerTeamIndex());

		JsonArray teams = new JsonArray();
		for (RoundSnapshot.Team t : snap.teams()) {
			JsonObject to = new JsonObject();
			to.addProperty("index", t.index());
			to.addProperty("name", t.name());
			to.addProperty("maxSize", t.maxSize());
			to.add("members", uuidArray(t.members()));
			teams.add(to);
		}
		o.add("teams", teams);

		JsonArray sessions = new JsonArray();
		for (RoundSnapshot.Session s : snap.sessions()) {
			JsonObject so = new JsonObject();
			so.addProperty("id", s.id().toString());
			so.addProperty("name", s.name());
			so.addProperty("teamIndex", s.teamIndex());
			so.addProperty("alive", s.alive());
			so.addProperty("eliminated", s.eliminated());
			so.addProperty("eliminatedTick", s.eliminatedTick());
			so.addProperty("disconnectTick", s.disconnectTick());
			so.addProperty("joinedQueueTick", s.joinedQueueTick());
			sessions.add(so);
		}
		o.add("sessions", sessions);
		o.add("queue", uuidArray(snap.queue()));

		JsonArray audit = new JsonArray();
		for (RoundMachine.Transition tr : snap.audit()) {
			JsonObject ao = new JsonObject();
			ao.addProperty("matchIndex", tr.matchIndex());
			ao.addProperty("totalTick", tr.totalTick());
			ao.addProperty("from", tr.from().name());
			ao.addProperty("event", tr.event().name());
			ao.addProperty("to", tr.to().name());
			audit.add(ao);
		}
		o.add("audit", audit);

		JsonArray notes = new JsonArray();
		snap.auditNotes().forEach(notes::add);
		o.add("auditNotes", notes);
		o.addProperty("arenaId", snap.arenaId());
		o.addProperty("formatId", snap.formatId());
		return o;
	}

	private static JsonArray uuidArray(List<UUID> ids) {
		JsonArray a = new JsonArray();
		for (UUID id : ids) {
			a.add(id.toString());
		}
		return a;
	}

	private static RoundSnapshot fromJson(JsonObject o, List<String> errors) {
		int schema = o.has("schemaVersion") ? o.get("schemaVersion").getAsInt() : 0;
		if (schema != SCHEMA_VERSION) {
			errors.add("schemaVersion=" + schema + "，当前只认 " + SCHEMA_VERSION);
		}
		Phase phase;
		try {
			phase = Phase.valueOf(str(o, "phase"));
		} catch (IllegalArgumentException e) {
			errors.add("phase 无法识别：" + str(o, "phase"));
			return null;
		}

		List<RoundSnapshot.Team> teams = new ArrayList<>();
		for (JsonElement e : arr(o, "teams")) {
			JsonObject t = e.getAsJsonObject();
			List<UUID> members = new ArrayList<>();
			for (JsonElement m : arr(t, "members")) {
				members.add(uuid(m.getAsString(), "teams[].members", errors));
			}
			teams.add(new RoundSnapshot.Team(intOf(t, "index"), str(t, "name"),
					intOf(t, "maxSize"), members));
		}

		List<RoundSnapshot.Session> sessions = new ArrayList<>();
		for (JsonElement e : arr(o, "sessions")) {
			JsonObject s = e.getAsJsonObject();
			sessions.add(new RoundSnapshot.Session(uuid(str(s, "id"), "sessions[].id", errors),
					str(s, "name"), intOf(s, "teamIndex"), s.get("alive").getAsBoolean(),
					s.get("eliminated").getAsBoolean(), intOf(s, "eliminatedTick"),
					intOf(s, "disconnectTick"), intOf(s, "joinedQueueTick")));
		}

		List<UUID> queue = new ArrayList<>();
		for (JsonElement e : arr(o, "queue")) {
			queue.add(uuid(e.getAsString(), "queue", errors));
		}

		List<RoundMachine.Transition> audit = new ArrayList<>();
		for (JsonElement e : arr(o, "audit")) {
			JsonObject a = e.getAsJsonObject();
			try {
				audit.add(new RoundMachine.Transition(intOf(a, "matchIndex"), intOf(a, "totalTick"),
						Phase.valueOf(str(a, "from")),
						RoundEvent.valueOf(str(a, "event")),
						Phase.valueOf(str(a, "to"))));
			} catch (IllegalArgumentException ex) {
				errors.add("audit 条目里的阶段或事件无法识别：" + a);
			}
		}

		List<String> notes = new ArrayList<>();
		for (JsonElement e : arr(o, "auditNotes")) {
			notes.add(e.getAsString());
		}

		String formatId = str(o, "formatId");
		if (formatId.isEmpty()) {
			errors.add("formatId 缺失：无法确定队伍规格，拒绝恢复");
		}
		return new RoundSnapshot(phase, intOf(o, "tickInPhase"), intOf(o, "totalTicks"),
				intOf(o, "matchIndex"), intOf(o, "matchStartTick"),
				o.get("autoStart").getAsBoolean(), intOf(o, "winnerTeamIndex"),
				teams, sessions, queue, audit, notes, str(o, "arenaId"), formatId);
	}

	private static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || !e.isJsonArray() ? new JsonArray() : e.getAsJsonArray();
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static int intOf(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? -1 : e.getAsInt();
	}

	private static UUID uuid(String raw, String where, List<String> errors) {
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			errors.add(where + " 不是合法 UUID：" + raw);
			return new UUID(0L, 0L);
		}
	}

	/** 供 /cww state 与日志用的一行摘要。 */
	public static String describe(Load load) {
		if (load.status() == Status.OK) {
			RoundSnapshot s = load.snapshot();
			return "phase=" + s.phase() + " match=" + s.matchIndex() + " tickInPhase=" + s.tickInPhase()
					+ " 参战=" + s.participantCount() + " 审计尾部=" + s.audit().size() + " 文件=" + load.file();
		}
		if (load.status() == Status.ABSENT) {
			return "没有落盘文件（首次启动，正常）：" + load.file();
		}
		return "落盘文件不可用（" + load.file() + "）：" + String.join("; ", load.errors());
	}

}
