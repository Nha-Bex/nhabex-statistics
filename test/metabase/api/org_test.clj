(ns metabase.api.org-test
  (:require [expectations :refer :all]
            [metabase.db :refer :all]
            [metabase.http-client :as http]
            [metabase.middleware.auth :as auth]
            (metabase.models [org :refer [Org]]
                             [org-perm :refer [OrgPerm]])
            [metabase.test-data :refer :all]
            [metabase.test-data.create :refer [create-user]]
            [metabase.test.util :refer [match-$ random-name expect-eval-actual-first]]))

;; Helper Fns

(defn create-org [org-name]
  {:pre [(string? org-name)]}
  ((user->client :crowberto) :post 200 "org" {:name org-name
                                              :slug org-name}))

(defn org-perm-exists? [org-id user-id]
  (exists? OrgPerm :organization_id org-id :user_id user-id))

(defn create-org-perm [org-id user-id & {:keys [admin]
                                         :or {admin false}}]
  ((user->client :crowberto) :post 200 (format "org/%d/members/%d" org-id user-id) {:admin admin}))


;; ## /api/org/* AUTHENTICATION Tests
;; We assume that all endpoints for a given context are enforced by the same middleware, so we don't run the same
;; authentication test on every single individual endpoint

(expect (get auth/response-unauthentic :body) (http/client :get 401 "org"))
(expect (get auth/response-unauthentic :body) (http/client :get 401 (format "org/%d" (:id @test-org))))


;; # GENERAL ORG ENDPOINTS

;; ## GET /api/org
;; Non-superusers should only be able to see Orgs they are members of
(let [org-name (random-name)]
  (expect-eval-actual-first
    [{:id @org-id
      :slug "test"
      :name "Test Organization"
      :description nil
      :logo_url nil
      :inherits true}]
    (do
      ;; Delete all the random test Orgs we've created
      (cascade-delete Org :id [not= (:id @test-org)])
      ;; Create a random Org so we ensure there is an Org that should NOT show up in our list
      (create-org org-name)
      ;; Now perform the API request
      ((user->client :rasta) :get 200 "org"))))

;; Superusers should be able to see all Orgs
(let [org-name (random-name)]
  (expect-eval-actual-first
      [{:id @org-id
        :slug "test"
        :name "Test Organization"
        :description nil
        :logo_url nil
        :inherits true}
       (match-$ (sel :one Org :name org-name)
         {:id $
          :slug $
          :name $
          :description nil
          :logo_url nil
          :inherits false})]
    (do
      ;; Delete all the random test Orgs we've created
      (cascade-delete Org :id [not= @org-id])
      ;; Create a random Org so we can check that we still get Orgs we're not members of
      (create-org org-name)
      ;; Now perform the API request
      ((user->client :crowberto) :get 200 "org"))))


;; ## POST /api/org
;; Check that non-superusers can't create Orgs
(expect "You don't have permissions to do that."
  (let [org-name (random-name)]
    ((user->client :rasta) :post 403 "org" {:name org-name
                                            :slug org-name})))

;; Check that superusers *can* create Orgs
(let [org-name (random-name)]
  (expect-eval-actual-first
    (match-$ (sel :one Org :name org-name)
      {:id $
       :slug org-name
       :name org-name
       :description nil
       :logo_url nil
       :inherits false})
    (create-org org-name)))

;; Test input validations on org create
(expect "'name' is a required param."
  ((user->client :crowberto) :post 400 "org" {}))

(expect "'slug' is a required param."
  ((user->client :crowberto) :post 400 "org" {:name "anything"}))


;; ## GET /api/org/:id
(expect
    {:id @org-id
     :slug "test"
     :name "Test Organization"
     :description nil
     :logo_url nil
     :inherits true}
  ((user->client :rasta) :get 200 (format "org/%d" @org-id)))

;; Check that non-superusers can't access orgs they don't have permissions to
(expect "You don't have permissions to do that."
  (let [org-name (random-name)
        my-org (create-org org-name)]
    ((user->client :rasta) :get 403 (format "org/%d" (:id my-org)))))

;; Test that invalid org id returns 404
(expect "Not found."
  ((user->client :rasta) :get 404 "org/1000"))

;; ## GET /api/org/slug/:slug
(expect
    {:id @org-id
     :slug "test"
     :name "Test Organization"
     :description nil
     :logo_url nil
     :inherits true}
  ((user->client :rasta) :get 200 (format "org/slug/%s" (:slug @test-org))))

;; Check that non-superusers can't access orgs they don't have permissions to
(expect "You don't have permissions to do that."
  (let [org-name (random-name)
        my-org (create-org org-name)]
    ((user->client :rasta) :get 403 (format "org/slug/%s" (:slug my-org)))))

;; Test that invalid org slug returns 404
(expect "Not found."
  ((user->client :rasta) :get 404 "org/slug/ksdlfkjdkfd"))


;; ## PUT /api/org/:id
;; Test that we can update an Org
(expect-let [orig-name (random-name)
             upd-name (random-name)
             {:keys [id slug inherits] :as org} (create-org orig-name)]
  {:id id
   :slug slug
   :name upd-name
   :description upd-name
   :logo_url upd-name
   :inherits false}
  ;; we try setting `slug` & `inherits` which should both remain unmodified
  ((user->client :crowberto) :put 200 (format "org/%d" id) {:slug upd-name
                                                            :name upd-name
                                                            :description upd-name
                                                            :logo_url upd-name
                                                            :inherits true}))

;; Check that non-superusers can't modify orgs they don't have permissions to
(expect "You don't have permissions to do that."
  (let [org-name (random-name)
        my-org (create-org org-name)]
    ((user->client :rasta) :put 403 (format "org/%d" (:id my-org)) {})))

;; Validate that write-perms are required to modify the org details (with user having read perms on org)
(expect "You don't have permissions to do that."
  (let [{user-id :id, email :email, password :first_name} (create-user)
        {org-id :id} (create-org (random-name))
        my-perm (create-org-perm org-id user-id :admin false)
        session-id (http/authenticate {:email email
                                       :password password})]
    (http/client session-id :put 403 (format "org/%d" org-id) {})))

;; Test that invalid org id returns 404
(expect "Not found."
  ((user->client :rasta) :put 404 "org/1000" {}))


;; # MEMBERS ENDPOINTS

;; ## GET /api/org/:id/members
(expect
    #{(match-$ (user->org-perm :crowberto)
        {:id $
         :admin true
         :user_id (user->id :crowberto)
         :organization_id @org-id
         :user (match-$ (fetch-user :crowberto)
                 {:common_name "Crowberto Corv"
                  :date_joined $
                  :last_name "Corv"
                  :id $
                  :is_superuser true
                  :last_login $
                  :first_name "Crowberto"
                  :email "crowberto@metabase.com"})})
      (match-$ (user->org-perm :trashbird)
        {:id $
         :admin false
         :user_id (user->id :trashbird)
         :organization_id @org-id
         :user (match-$ (fetch-user :trashbird)
                 {:common_name "Trash Bird"
                  :date_joined $
                  :last_name "Bird"
                  :id $
                  :is_superuser false
                  :last_login $
                  :first_name "Trash"
                  :email "trashbird@metabase.com"})})
      (match-$ (user->org-perm :lucky)
        {:id $
         :admin false
         :user_id (user->id :lucky)
         :organization_id @org-id
         :user (match-$ (fetch-user :lucky)
                 {:common_name "Lucky Pigeon"
                  :date_joined $
                  :last_name "Pigeon"
                  :id $
                  :is_superuser false
                  :last_login $
                  :first_name "Lucky"
                  :email "lucky@metabase.com"})})
      (match-$ (user->org-perm :rasta)
        {:id $
         :admin true
         :user_id (user->id :rasta)
         :organization_id @org-id
         :user (match-$ (fetch-user :rasta)
                 {:common_name "Rasta Toucan"
                  :date_joined $
                  :last_name "Toucan"
                  :id $
                  :is_superuser false
                  :last_login $
                  :first_name "Rasta"
                  :email "rasta@metabase.com"})})}
  (set ((user->client :rasta) :get 200 (format "org/%d/members" @org-id))))

;; Check that users without any org perms cannot list members
(expect "You don't have permissions to do that."
  (let [{:keys [id]} (create-org (random-name))]
    ((user->client :rasta) :get 403 (format "org/%d/members" id) {})))

;; Test that invalid org id returns 404
(expect "Not found."
  ((user->client :rasta) :get 404 "org/1000/members"))


;; ## POST /api/org/:id/members/:user-id
;; Check that we can create an OrgPerm between existing User + Org
(expect [false
         true]
  (let [{org-id :id} (create-org (random-name))
        {user-id :id} (create-user)
        org-perm-exists? (partial org-perm-exists? org-id user-id)]
    [(org-perm-exists?)
     (do (create-org-perm org-id user-id)
         (org-perm-exists?))]))

;; ## DELETE /api/org/:id/members/:user-id
;; Check we can delete OrgPerms between a User + Org
(expect [false
         true
         false]
  (let [{org-id :id} (create-org (random-name))
        {user-id :id} (create-user)
        org-perm-exists? (partial org-perm-exists? org-id user-id)]
    [(org-perm-exists?)
     (do (create-org-perm org-id user-id)
         (org-perm-exists?))
     (do ((user->client :crowberto) :delete 204 (format "org/%d/members/%d" org-id user-id))
         (org-perm-exists?))]))

;; ## PUT /api/org/:id/members/:user-id
;; Check that we can edit an exisiting OrgPerm (i.e., toggle 'admin' status)
(expect
    [nil
     false
     true]
  (let [{org-id :id} (create-org (random-name))
        {user-id :id} (create-user)
        is-admin? (fn [] (sel :one :field [OrgPerm :admin] :user_id user-id :organization_id org-id))]
    [(is-admin?)
     (do (create-org-perm org-id user-id)
         (is-admin?))
     (do ((user->client :crowberto) :put 200 (format "org/%d/members/%d" org-id user-id) {:admin true})
         (is-admin?))]))
