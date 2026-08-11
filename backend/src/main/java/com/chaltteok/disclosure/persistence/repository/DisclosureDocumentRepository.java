package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.DisclosureDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureDocumentRepository extends JpaRepository<DisclosureDocumentEntity, String> {
}
