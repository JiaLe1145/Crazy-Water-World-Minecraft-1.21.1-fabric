package dev.nbw.domain.round;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 队伍与席位（04 §2 数据模型）。成员按加入顺序保存，便于"先申请先进队"（01 §2）。 */
public final class MatchTeam {

	private final int index;
	private final String name;
	private final int maxSize;
	private final Set<UUID> members = new LinkedHashSet<>();

	public MatchTeam(int index, String name, int maxSize) {
		this.index = index;
		this.name = name;
		this.maxSize = maxSize;
	}

	public int index() {
		return index;
	}

	public String name() {
		return name;
	}

	public int maxSize() {
		return maxSize;
	}

	public Set<UUID> members() {
		return Collections.unmodifiableSet(members);
	}

	public List<UUID> membersOrdered() {
		return new ArrayList<>(members);
	}

	public boolean isFull() {
		return members.size() >= maxSize;
	}

	public boolean contains(UUID id) {
		return members.contains(id);
	}

	/** @return null 成功，否则为拒绝原因（绝不静默丢弃报名）。 */
	public String add(UUID id) {
		if (members.contains(id)) {
			return null;
		}
		if (members.size() >= maxSize) {
			return "队伍 " + name + " 已满（上限 " + maxSize + "）";
		}
		members.add(id);
		return null;
	}

	public void remove(UUID id) {
		members.remove(id);
	}

	public void clear() {
		members.clear();
	}

	public int size() {
		return members.size();
	}
}
