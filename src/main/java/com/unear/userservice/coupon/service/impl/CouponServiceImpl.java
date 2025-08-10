package com.unear.userservice.coupon.service.impl;

import com.unear.userservice.benefit.entity.FranchiseDiscountPolicy;
import com.unear.userservice.benefit.entity.GeneralDiscountPolicy;
import com.unear.userservice.benefit.repository.FranchiseDiscountPolicyRepository;
import com.unear.userservice.benefit.repository.GeneralDiscountPolicyRepository;
import com.unear.userservice.common.enums.*;
import com.unear.userservice.common.exception.exception.*;
import com.unear.userservice.common.redis.producer.UserActionLogProducer;
import com.unear.userservice.common.util.LogMetadataUtils;
import com.unear.userservice.coupon.dto.response.CouponResponseDto;
import com.unear.userservice.coupon.dto.response.UserCouponDetailResponseDto;
import com.unear.userservice.coupon.dto.response.UserCouponListResponseDto;
import com.unear.userservice.coupon.dto.response.UserCouponResponseDto;
import com.unear.userservice.coupon.entity.CouponTemplate;
import com.unear.userservice.coupon.entity.UserCoupon;
import com.unear.userservice.coupon.repository.CouponTemplateRepository;
import com.unear.userservice.coupon.repository.UserCouponRepository;
import com.unear.userservice.coupon.service.CouponService;
import com.unear.userservice.place.entity.Franchise;
import com.unear.userservice.place.entity.Place;
import com.unear.userservice.place.repository.PlaceRepository;
import com.unear.userservice.user.entity.User;
import com.unear.userservice.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final GeneralDiscountPolicyRepository generalDiscountPolicyRepository;
    private final FranchiseDiscountPolicyRepository franchiseDiscountPolicyRepository;
    private final PlaceRepository placeRepository;
    private final CouponTemplateRepository couponTemplateRepository;
    private final UserCouponRepository userCouponRepository;
    private final UserRepository userRepository;
    private final UserActionLogProducer userActionLogProducer;

    @Override
    @Transactional
    public List<CouponResponseDto> getCouponsByPlaceAndMarker(Long userId, Long placeId, String markerCode) {
        PlaceType placeType = PlaceType.fromCode(markerCode);

        List<Long> discountPolicyIds = List.of();
        if (placeType.isBasic()) {
            discountPolicyIds = generalDiscountPolicyRepository.findPolicyIdsByPlaceId(placeId);
        } else if (placeType.isFranchise()) {
            Optional<Long> franchiseIdOpt = placeRepository.findFranchiseIdByPlaceId(placeId);
            if (franchiseIdOpt.isEmpty()) return List.of();
            Long franchiseId = franchiseIdOpt.get();
            discountPolicyIds = franchiseDiscountPolicyRepository.findPolicyIdsByFranchiseId(franchiseId);
        }

        if (discountPolicyIds.isEmpty()) return List.of();

        List<CouponTemplate> templates = couponTemplateRepository
                .findByDiscountPolicyDetailIdInAndMarkerCode(discountPolicyIds, placeType.getCode());


        Set<Long> downloadedIds = (userId != null)
                ? userCouponRepository.findCouponTemplateIdsByUserId(userId)
                : Set.of();

        final String userMembershipCode =
                (userId != null && placeType.isFranchise())
                        ? userRepository.findMembershipCodeByUserId(userId)
                        : null;

        return templates.stream()
                .filter(template -> {

                    if (!placeType.isFranchise()) return true;

                    String templateMembershipCode = template.getMembershipCode();
                    if (templateMembershipCode == null) return false;

                    if (MembershipGrade.isAll(templateMembershipCode)) return true;

                    if (userMembershipCode == null) return false;

                    return templateMembershipCode.equalsIgnoreCase(userMembershipCode);
                })
                .map(template -> {
                    String discountInfo = DiscountPolicy.fromCode(template.getDiscountCode().getCode()).getLabel();
                    boolean isDownloaded = downloadedIds.contains(template.getCouponTemplateId());
                    return CouponResponseDto.from(template, discountInfo, isDownloaded , null);
                })
                .toList();
    }


    @Override
    @Transactional
    public UserCouponResponseDto downloadCoupon(Long userId, Long couponTemplateId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("사용자를 찾을 수 없습니다."));

        CouponTemplate template = couponTemplateRepository.findById(couponTemplateId)
                .orElseThrow(() -> new CouponTemplateNotFoundException("쿠폰 템플릿을 찾을 수 없습니다."));

        LocalDateTime now = LocalDateTime.now();
        if (template.getCouponStart().isAfter(now) || template.getCouponEnd().isBefore(now)) {
            throw new CouponExpiredException("유효 기간이 지난 쿠폰입니다.");
        }

        UserCoupon userCoupon = UserCoupon.builder()
                .user(user)
                .couponTemplate(template)
                .createdAt(LocalDateTime.now())
                .couponStatusCode(CouponStatus.UNUSED.getCode())
                .barcodeNumber(generateUniqueBarcode())
                .build();

        Map<String, Object> baseMetadata = LogMetadataUtils.buildUserBaseMetadata(user);

        if (template.getDiscountCode() != null) {
            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
            metadata.put("benefit", template.getDiscountCode());
            userActionLogProducer.logUserAction(userId, UserActionType.DOWNLOAD_COUPON, "mapPage", metadata);
        }

        try {
            userCouponRepository.save(userCoupon);
        } catch (DataIntegrityViolationException e) {
            throw new CouponAlreadyDownloadedException("이미 다운로드한 쿠폰입니다.");
        }

        return UserCouponResponseDto.from(userCoupon);
    }

    // 바깥 메서드: 트랜잭션 없이 재시도 루프
    public UserCouponResponseDto downloadFCFSCoupon(Long userId, Long couponTemplateId) {
        final int maxRetries = 3;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return issueOnceWithTx(userId, couponTemplateId); // 트랜잭션 안에서 1회 시도
            } catch (ObjectOptimisticLockingFailureException | jakarta.persistence.OptimisticLockException e) {
                // 동시 갱신 충돌 → 짧은 지터 백오프 후 재시도
                if (attempt == maxRetries) {
                    // 재시도 한계 초과: 소진/경합 과다로 처리
                    throw new CouponSoldOutException("요청이 몰려 쿠폰 발급에 실패했습니다.");
                }
                try { Thread.sleep(20L * attempt); } catch (InterruptedException ignored) {}
            }
        }
        throw new IllegalStateException("쿠폰 발급 처리에 실패했습니다.");
    }

    // 내부 메서드: 실제 발급 로직 (매 호출마다 새로운 트랜잭션)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected UserCouponResponseDto issueOnceWithTx(Long userId, Long couponTemplateId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("사용자를 찾을 수 없습니다."));

        // ★ 일반 조회 (낙관적 락은 커밋/flush 시점에 버전 비교로 충돌을 감지)
        CouponTemplate template = couponTemplateRepository.findById(couponTemplateId)
                .orElseThrow(() -> new CouponTemplateNotFoundException("쿠폰 템플릿을 찾을 수 없습니다."));

        LocalDateTime now = LocalDateTime.now();
        if (template.getCouponStart().isAfter(now) || template.getCouponEnd().isBefore(now)) {
            throw new CouponExpiredException("유효 기간이 지난 쿠폰입니다.");
        }

        // 재고 차감 (동시 갱신 시 커밋 시점에 OptimisticLockException 발생)
        template.decreaseQuantity();

        UserCoupon userCoupon = UserCoupon.builder()
                .user(user)
                .couponTemplate(template)
                .createdAt(LocalDateTime.now())
                .couponStatusCode(CouponStatus.UNUSED.getCode())
                .barcodeNumber(generateUniqueBarcode())
                .build();

        try {
            userCouponRepository.save(userCoupon);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new CouponAlreadyDownloadedException("이미 다운로드한 쿠폰입니다.");
        }

        // 트랜잭션 커밋 시점에 JPA가 version을 비교하며 충돌을 감지/예외 발생
        return UserCouponResponseDto.from(userCoupon);
    }


    @Override
    @Transactional
    public UserCouponListResponseDto getMyCoupons(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("사용자를 찾을 수 없습니다."));

        List<UserCoupon> userCoupons = userCouponRepository.findByUser_UserId(userId);

        List<UserCouponResponseDto> dtoList = userCoupons.stream()
                .filter(uc -> CouponStatus.fromCode(uc.getCouponStatusCode()).equals(CouponStatus.UNUSED))
                .map(uc -> {
                    CouponTemplate template = uc.getCouponTemplate();
                    String markerCode = template != null ? template.getMarkerCode() : null;
                    Long policyId = (template != null) ? template.getDiscountPolicyDetailId() : null;

                    GeneralDiscountPolicy generalPolicy = null;
                    FranchiseDiscountPolicy franchisePolicy = null;
                    Franchise franchise = null;
                    Place place = null;

                    if (markerCode != null && policyId != null) {
                        PlaceType placeType = PlaceType.fromCode(markerCode);
                        if (placeType.isFranchise()) {
                            franchisePolicy = franchiseDiscountPolicyRepository.findById(policyId).orElse(null);
                            franchise = franchisePolicy != null ? franchisePolicy.getFranchise() : null;
                        } else {
                            generalPolicy = generalDiscountPolicyRepository.findById(policyId).orElse(null);
                            place = generalPolicy != null ? generalPolicy.getPlace() : null;
                        }
                    }

                    return UserCouponResponseDto.builder()
                            .userCouponId(uc.getUserCouponId())
                            .couponName(template != null ? template.getCouponName() : null)
                            .barcodeNumber(uc.getBarcodeNumber())
                            .couponStatusCode(uc.getCouponStatusCode())
                            .createdAt(uc.getCreatedAt())
                            .couponEnd(template != null ? template.getCouponEnd() : null)
                            .name(resolveFranchiseName(place, franchise))
                            .imageUrl(franchise != null ? franchise.getImageUrl() : null)
                            .categoryCode(
                                    place != null ? place.getCategoryCode() :
                                            (franchise != null ? franchise.getCategoryCode() : null)
                            )
                            .markerCode(markerCode)
                            .build();
                })
                .toList();

        return new UserCouponListResponseDto(dtoList);
    }


    private String resolveFranchiseName(Place place, Franchise franchise) {
        if (franchise != null) {
            return franchise.getName();
        }
        if (place != null) {
            return place.getPlaceName();
        }
        return null;
    }



    @Override
    public UserCouponDetailResponseDto getMyCouponDetail(Long userId, Long userCouponId) {
        UserCoupon userCoupon = userCouponRepository.findByUserCouponIdAndUser_UserId(userCouponId, userId)
                .orElseThrow(() -> new UserCouponNotFoundException("다운받은 쿠폰을 찾을 수 없습니다."));
        CouponTemplate template = userCoupon.getCouponTemplate();
        String markerCode = template.getMarkerCode();

        UserCouponDetailResponseDto.UserCouponDetailResponseDtoBuilder builder = UserCouponDetailResponseDto.builder()
                .userCouponId(userCoupon.getUserCouponId())
                .couponName(template.getCouponName())
                .barcodeNumber(userCoupon.getBarcodeNumber())
                .couponStatusCode(userCoupon.getCouponStatusCode())
                .createdAt(userCoupon.getCreatedAt())
                .couponEnd(template.getCouponEnd())
                .markerCode(markerCode);

        if (template.getDiscountPolicyDetailId() == null) {
            return builder.build();
        }

        PlaceType placeType = PlaceType.fromCode(markerCode);
        if (placeType.isFranchise()) {
            franchiseDiscountPolicyRepository.findWithFranchiseById(template.getDiscountPolicyDetailId()).ifPresent(policy ->
                    builder
                            .discountCode(policy.getDiscountCode())
                            .brandName(policy.getFranchise() != null ? policy.getFranchise().getName() : null)
                            .membershipCode(policy.getMembershipCode())
                            .fixedDiscount(policy.getFixedDiscount())
                            .discountPercent(policy.getDiscountPercent())
                            .minPurchaseAmount(policy.getMinPurchaseAmount())
                            .maxDiscountAmount(policy.getMaxDiscountAmount())
            );
        } else if (placeType.isBasic()) {
            generalDiscountPolicyRepository.findById(template.getDiscountPolicyDetailId()).ifPresent(policy ->
                    builder
                            .discountCode(policy.getDiscountCode())
                            .membershipCode(policy.getMembershipCode())
                            .fixedDiscount(policy.getFixedDiscount())
                            .discountPercent(policy.getDiscountPercent())
                            .minPurchaseAmount(policy.getMinPurchaseAmount())
                            .maxDiscountAmount(policy.getMaxDiscountAmount())
            );
        }

        return builder.build();
    }



    private String generateUniqueBarcode() {
        for (int i = 0; i < 3; i++) {
            String barcode = generateBarcode();
            if (!userCouponRepository.existsByBarcodeNumber(barcode)) {
                return barcode;
            }
        }
        throw new BarcodeDuplicatedException("중복된 바코드로 인해 바코드 생성 실패");
    }

    private String generateBarcode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }


}
