package com.unear.userservice.coupon.repository;

import com.unear.userservice.common.enums.DiscountPolicy;
import com.unear.userservice.coupon.entity.CouponTemplate;
import com.unear.userservice.place.entity.Franchise;
import com.unear.userservice.place.entity.Place;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, Long> {
    List<CouponTemplate> findByDiscountPolicyDetailIdInAndMarkerCode(List<Long> discountPolicyIds, String markerCode);

@Query("""
SELECT ct
FROM CouponTemplate ct
WHERE ct.event.unearEventId = :eventId
AND ct.discountCode = :discountCode
""")
List<CouponTemplate> findByEventCoupon(
    @Param("eventId") Long eventId,
    @Param("discountCode") DiscountPolicy discountPolicy  
);

    @Query("""
SELECT ct FROM CouponTemplate ct
WHERE (
    ct.markerCode = 'FRANCHISE'
    AND ct.discountPolicyDetailId IN (
        SELECT fdp.franchiseDiscountPolicyId
        FROM FranchiseDiscountPolicy fdp
        WHERE fdp.franchise IN :franchises
          AND (fdp.membershipCode = :membershipCode OR fdp.membershipCode = 'ALL')
    )
) OR (
    ct.markerCode != 'FRANCHISE'
    AND ct.discountPolicyDetailId IN (
        SELECT gdp.generalDiscountPolicyId
        FROM GeneralDiscountPolicy gdp
        WHERE gdp.place IN :places
          AND (gdp.membershipCode = :membershipCode OR gdp.membershipCode = 'ALL')
    )
)
""")
    List<CouponTemplate> findByPlacesAndMembership(
            @Param("places") List<Place> places,
            @Param("franchises") List<Franchise> franchises,
            @Param("membershipCode") String membershipCode
    );


    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE CouponTemplate c
           SET c.remainingQuantity = c.remainingQuantity - 1
         WHERE c.couponTemplateId = :id
           AND c.remainingQuantity > 0
           AND :now BETWEEN c.couponStart AND c.couponEnd
    """)
    int decreaseIfAvailable(@Param("id") Long id, @Param("now") LocalDateTime now);

}
