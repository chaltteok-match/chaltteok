package com.chaltteok.disclosure.persistence.repository;

import com.chaltteok.disclosure.persistence.entity.ChangedFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChangedFieldRepository extends JpaRepository<ChangedFieldEntity, Long> {
}
