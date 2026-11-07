package com.omiyage.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.ResolvableType
import org.springframework.core.io.ClassPathResource
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.parser.PartTree
import java.util.Properties

/** Guards #75: SQL lives only in META-INF/jpa-named-queries.properties and takes input only through bind parameters. */
class RepositoryQueryGuardTest {

    private val repositories: List<Class<*>> = object : ClassPathScanningCandidateComponentProvider(false) {
        override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) = beanDefinition.metadata.isInterface
    }.apply { addIncludeFilter(AssignableTypeFilter(Repository::class.java)) }
        .findCandidateComponents("com.omiyage.backend")
        .map { Class.forName(it.beanClassName) }

    private val namedQueries = Properties().apply {
        ClassPathResource("META-INF/jpa-named-queries.properties").inputStream.use { load(it) }
    }

    private fun entityClass(repository: Class<*>): Class<*> =
        requireNotNull(ResolvableType.forClass(repository).`as`(Repository::class.java).getGeneric(0).resolve())

    private fun entityName(repository: Class<*>): String = entityClass(repository).simpleName

    /** Spring Data derives a query from the method name only if the name parses against the entity's properties. */
    private fun isDerived(methodName: String, entity: Class<*>): Boolean = runCatching { PartTree(methodName, entity) }.isSuccess

    /** Methods Spring Data cannot derive from their name: each needs a statement in the file. */
    private val nonDerivedMethods: List<String> = repositories.flatMap { repository ->
        val entity = entityClass(repository)
        repository.declaredMethods
            .filterNot { it.isDefault || it.isSynthetic }
            .filter { it.getAnnotation(Query::class.java)?.nativeQuery == true || !isDerived(it.name, entity) }
            .map { "${entity.simpleName}.${it.name}" }
    }

    @Test
    fun `the scan finds the repositories`() {
        assertThat(repositories.map { it.simpleName }).contains("OrderRepository", "OrderItemRepository", "MediaRepository")
    }

    @Test
    fun `no repository method carries SQL inline`() {
        val inline = repositories.flatMap { repository ->
            repository.declaredMethods
                .filter { method -> method.getAnnotation(Query::class.java)?.let { it.value.isNotEmpty() || it.countQuery.isNotEmpty() } == true }
                .map { "${repository.simpleName}.${it.name}" }
        }

        assertThat(inline).isEmpty()
    }

    @Test
    fun `every method that is not a derived query has its statement in the file`() {
        assertThat(nonDerivedMethods.filterNot { namedQueries.containsKey(it) }).isEmpty()
    }

    @Test
    fun `JPQL methods without an annotation count as non-derived`() {
        assertThat(nonDerivedMethods).contains(
            "InventoryItem.search",
            "Order.findFiltered",
            "Order.findAllOrderByItemCountAsc",
            "Order.findAllOrderByItemCountDesc",
            "Order.detachShipment",
        )
        assertThat(nonDerivedMethods).doesNotContain("Order.findByUserId", "Order.existsByShipment_Id")
    }

    @Test
    fun `every statement in the file belongs to a repository method`() {
        val methods = repositories.flatMap { repository -> repository.declaredMethods.map { "${entityName(repository)}.${it.name}" } }.toSet()

        assertThat(namedQueries.stringPropertyNames().map { it.removeSuffix(".count") }).allMatch { it in methods }
    }

    @Test
    fun `statements take input only through named bind parameters`() {
        namedQueries.stringPropertyNames().forEach { key ->
            val statement = namedQueries.getProperty(key)
            assertThat(statement).`as`(key).doesNotContain("\${").doesNotContain("#{").doesNotContain("?")
        }
    }
}
