package com.omiyage.backend.order

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import org.assertj.core.api.Assertions.assertThat

class OrderStatusMachineProperties {
    private val terminal = setOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)

    @Property
    fun `staying in the same status is always allowed`(@ForAll status: OrderStatus) {
        assertThat(status.canMoveTo(status)).isTrue()
    }

    @Property
    fun `terminal statuses only allow staying`(@ForAll from: OrderStatus, @ForAll to: OrderStatus) {
        if (from in terminal && from != to) assertThat(from.canMoveTo(to)).isFalse()
    }

    @Property
    fun `every non-terminal status can be cancelled`(@ForAll from: OrderStatus) {
        if (from !in terminal) assertThat(from.canMoveTo(OrderStatus.CANCELLED)).isTrue()
    }

    @Property
    fun `forward jumps of any size are allowed`(@ForAll from: OrderStatus, @ForAll to: OrderStatus) {
        if (from !in terminal && to.ordinal > from.ordinal) assertThat(from.canMoveTo(to)).isTrue()
    }

    @Property
    fun `backward moves are allowed by exactly one step`(@ForAll from: OrderStatus, @ForAll to: OrderStatus) {
        if (from !in terminal && to != OrderStatus.CANCELLED && to.ordinal < from.ordinal) {
            assertThat(from.canMoveTo(to)).isEqualTo(to.ordinal == from.ordinal - 1)
        }
    }

    @Property
    fun `a terminal status is absorbing under any random walk`(
        @ForAll start: OrderStatus,
        @ForAll attempts: List<OrderStatus>,
    ) {
        if (start !in terminal) return
        var current = start
        for (target in attempts) {
            if (current.canMoveTo(target)) current = target
        }
        assertThat(current).isEqualTo(start)
    }
}
