package com.aifds.backend.fraudcase.repository;

import com.aifds.backend.fraudcase.entity.InvestigationNote;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvestigationNoteRepository
        extends JpaRepository<InvestigationNote, Long> {

    @Query(
            value = """
                    SELECT note FROM InvestigationNote note
                    WHERE note.fraudCaseId = :fraudCaseId
                    ORDER BY note.createdAt ASC, note.id ASC
                    """,
            countQuery = """
                    SELECT COUNT(note) FROM InvestigationNote note
                    WHERE note.fraudCaseId = :fraudCaseId
                    """
    )
    Page<InvestigationNote> findPageAscending(
            @Param("fraudCaseId") Long fraudCaseId,
            Pageable pageable
    );

    @Query(
            value = """
                    SELECT note FROM InvestigationNote note
                    WHERE note.fraudCaseId = :fraudCaseId
                    ORDER BY note.createdAt DESC, note.id DESC
                    """,
            countQuery = """
                    SELECT COUNT(note) FROM InvestigationNote note
                    WHERE note.fraudCaseId = :fraudCaseId
                    """
    )
    Page<InvestigationNote> findPageDescending(
            @Param("fraudCaseId") Long fraudCaseId,
            Pageable pageable
    );
}
