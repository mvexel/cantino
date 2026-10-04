package lol.osm.cantino.cafe

import lol.osm.cantino.ImportProfile
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore

/** One relation member as the detail screen shows it. Built on the store's thread. */
internal data class RelationMemberRow(val member: OsmObject.Member, val present: Boolean, val name: String?) {
    /**
     * The row text; absent members say so instead of linking. With the app's
     * import profile they are still "not in this area" (outside it), never
     * filtered out: the import keeps every member of a kept relation.
     */
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

/**
 * The detail screen's text for an object the area does not contain. The app
 * downloads with an import profile ([CafeProfile]), so the object may be
 * outside the area *or* filtered out, and the area cannot tell which: both
 * causes are named, with the profile when the area records one.
 */
internal fun notFoundText(profile: ImportProfile?): String =
    "Not in this area or filtered out: the offline area does not contain this object. It lies outside the area " +
        "(or was clipped at its edge), or the import profile" +
        (CafeProfile.label(profile)?.let { " ($it)" } ?: "") + " did not keep it."
