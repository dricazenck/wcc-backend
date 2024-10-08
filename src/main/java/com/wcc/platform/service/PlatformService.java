package com.wcc.platform.service;

import com.wcc.platform.domain.platform.Member;
import com.wcc.platform.repository.MemberRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Platform service. */
@Service
public class PlatformService {

  private final MemberRepository memberRepository;

  @Autowired
  public PlatformService(final MemberRepository memberRepository) {
    this.memberRepository = memberRepository;
  }

  /** Save Member into storage. */
  public Member createMember(final Member member) {
    return memberRepository.save(member);
  }

  /**
   * Return all stored members.
   *
   * @return List of members.
   */
  public List<Member> getAll() {
    final var allMembers = memberRepository.getAll();
    if (allMembers == null) {
      return List.of();
    }
    return allMembers;
  }
}
