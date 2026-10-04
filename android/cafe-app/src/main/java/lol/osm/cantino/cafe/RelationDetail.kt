package lol.osm.cantino.cafe

import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore

/** One relation member as the detail screen shows it. Built on the store's thread. */
internal data class RelationMemberRow(val member: OsmObject.Member, val present: Boolean, val name: String?) {
    /** The row text; absent members (outside the area) say so instead of linking. */
    fun label(index: Int): String {
        val role = member.role.ifEmpty { "(no role)" }
        val target = "${member.id.kind.name.lowercase()} ${member.id.id}"
        return "${index + 1}. $role · $target" +
            (name?.let { " · $it" } ?: "") + if (present) "" else " · not in this area"
    }
}

internal fun relationMemberRows(store: OsmStore, relation: OsmObject.Relation): List<RelationMemberRow> =
    relation.members.map { member ->
        val target = store.get(member.id)
        RelationMemberRow(member, target != null, target?.tags?.get("name"))
    }
